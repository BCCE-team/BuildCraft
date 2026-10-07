#!/usr/bin/env python3
"""Run BuildCraft validation, AutoTests and GameTests locally.

The local runner executes repository validators first, then builds/tests every
maintained target and runs its GameTests. Client and dedicated-server smoke
tests remain GitHub CI acceptance gates and are intentionally not launched by
the local test runner. Step transcripts and build/GameTest artifacts are copied
under logs/ci-local/<run-id>/.
"""
from __future__ import annotations

import argparse
import datetime as dt
import glob
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from typing import Iterable, Sequence

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
LOG_ROOT = ROOT / "logs" / "ci-local"
TARGETS = (
    ("1.19.2-forge", "old", 17),
    ("1.20.1-forge", "old", 17),
    ("1.21.1-neoforge", "1.21.X", 21),
    ("1.21.11-neoforge", "1.21.X", 21),
    ("26.1.2-neoforge", "26.X", 25),
)
VALIDATE_STEPS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("Validate independent Stonecutter builds", (sys.executable, "scripts/validate-stonecutter.py")),
    ("Validate hybrid source layout", (sys.executable, "scripts/validate-source-families.py")),
    # Architecture hardening is assembled at runtime because BASE_SHA is optional in the workflow.
    ("Enforce architecture budgets and trend", ("__ARCHITECTURE__",)),
    ("Validate API v2 boundary", (sys.executable, "scripts/validate-api-v2.py")),
    ("Validate API v2-only public surface", (sys.executable, "scripts/validate-api-v2-only.py")),
    ("Validate API2 runtime completeness", (sys.executable, "scripts/validate-api2-runtime-completeness.py")),
    ("Validate API2 module contracts", (sys.executable, "scripts/validate-api2-modules.py")),
    ("Validate repository cleanliness", (sys.executable, "scripts/validate-repository-cleanliness.py")),
    ("Validate cross-version gameplay parity", (sys.executable, "scripts/validate-behavior-parity.py")),
    ("Validate 1.21.11 parity", (sys.executable, "scripts/validate-1.21.11-parity.py")),
    ("Validate 26.1.2 target structure", (sys.executable, "scripts/validate-26.1.2-target.py")),
    ("Validate Zone Planner block preview parity", (sys.executable, "scripts/validate-zone-planner-preview.py")),
    ("Validate FE compatibility", (sys.executable, "scripts/validate-fe-compat.py")),
    ("Validate FE Engine and MJ Dynamo parity", (sys.executable, "scripts/validate-fe-mj-engine-parity.py")),
    ("Validate JEI crafting layout parity", (sys.executable, "scripts/validate-jei-crafting-layouts.py")),
    ("Validate gameplay, render and performance regressions", (sys.executable, "scripts/validate-regressions.py")),
    ("Validate guidebook runtime claims", (sys.executable, "scripts/validate-guide-runtime-claims.py")),
    ("Test internal Minecraft compatibility boundaries", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_minecraft_compat.py", "-v")),
    ("Test actor tickets and client registration boundaries", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_actor_client_boundaries.py", "-v")),
    ("Test loader-neutral packet boundaries", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_loader_boundaries.py", "-v")),
    ("Test storage, event, registry and config boundaries", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_platform_boundaries.py", "-v")),
    ("Test capability lifecycle invalidation and revival", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_capability_lifecycle.py", "-v")),
    ("Test platform contracts", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_platform_contracts.py", "-v")),
    ("Test pure Java gameplay algorithms", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_pure_logic.py", "-v")),
    ("Test pipe items, recipe book and optional client integrations", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_client_integrations.py", "-v")),
    ("Test GUI regressions", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_gui_regressions.py", "-v")),
    ("Test blueprint inventory-copy edge cases", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_inventory_copy_edge_cases.py", "-v")),
    ("Test Silicon recipe discovery and selection", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_silicon_recipe_book.py", "-v")),
    ("Test Guide Book filtering pagination and live previews", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_guide_book_features.py", "-v")),
    ("Test ownership persistence and ledgers", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_ownership_ledgers.py", "-v")),
    ("Test canonical resource pipeline", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_resource_pipeline.py", "-v")),
    ("Validate resources, metadata and build hygiene", (sys.executable, "scripts/validate-cross-target-integrity.py")),
)

class LocalCIError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="Run only repository validators/AutoTests and skip target builds/GameTests.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Write/print the exact local execution plan without running commands.",
    )
    parser.add_argument(
        "--run-id",
        help="Override the logs/ci-local run directory name (default: local timestamp).",
    )
    return parser.parse_args()


def slug(value: str) -> str:
    value = re.sub(r"[^A-Za-z0-9._-]+", "-", value.strip()).strip("-")
    return value or "step"


def workflow_alignment_check() -> None:
    """Fail closed when CI changes the validation or target test contract used locally."""
    text = WORKFLOW.read_text(encoding="utf-8")
    validate_start = text.find("  validate:")
    build_start = text.find("  build-test-server:")
    compat_start = text.find("  Compatibility:")
    if validate_start < 0 or build_start < 0 or validate_start >= build_start:
        raise LocalCIError("Unable to identify validate/build jobs in .github/workflows/ci.yml")
    build_end = compat_start if compat_start > build_start else len(text)

    validate_text = text[validate_start:build_start]
    position = 0
    for name, _ in VALIDATE_STEPS:
        token = f"- name: {name}"
        found = validate_text.find(token, position)
        if found < 0:
            raise LocalCIError(f"Local test runner is stale: workflow step missing/out of order: {name}")
        position = found + len(token)

    required_validate_fragments = (
        "python scripts/validate-architecture-hardening.py",
        "python scripts/validate-api2-modules.py",
        "python scripts/validate-1.21.11-parity.py",
        "python scripts/validate-26.1.2-target.py",
        "python scripts/validate-cross-target-integrity.py",
    )
    for fragment in required_validate_fragments:
        if fragment not in validate_text:
            raise LocalCIError(f"Local test runner is stale: workflow no longer contains {fragment!r}")

    build_text = text[build_start:build_end]
    build_step_names = (
        "Validate Forge 1.20.1 target invariants",
        "Build and test target",
        "Run GameTests",
    )
    position = 0
    for name in build_step_names:
        found = build_text.find(f"- name: {name}", position)
        if found < 0:
            raise LocalCIError(f"Local test runner is stale: build step missing/out of order: {name}")
        position = found + len(name)
    for target, generation, java in TARGETS:
        block = f"- target: {target}\n            generation: {generation}\n            java: '{java}'"
        if block not in build_text:
            raise LocalCIError(f"Local test runner is stale: build matrix entry changed for {target}")
    for fragment in (
        '":${STONECUTTER_TARGET}:buildAndCollect"',
        '":${STONECUTTER_TARGET}:runGameTestServer"',
    ):
        if fragment not in build_text:
            raise LocalCIError(f"Local test runner is stale: target test command changed: {fragment}")

def java_major(java_exe: Path) -> int | None:
    try:
        completed = subprocess.run(
            [str(java_exe), "-version"],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    output = completed.stdout or ""
    match = re.search(r'version "(?:1\.)?(\d+)', output)
    if match is None:
        match = re.search(r'openjdk\s+(?:version\s+)?(?:"?)(?:1\.)?(\d+)', output, re.IGNORECASE)
    return int(match.group(1)) if match else None


def java_executable(home: Path) -> Path:
    return home / "bin" / ("java.exe" if os.name == "nt" else "java")


def _reported_java_home(java_exe: Path) -> Path | None:
    """Ask the JVM for java.home so PATH shims/symlinks resolve to the real runtime."""
    try:
        completed = subprocess.run(
            [str(java_exe), "-XshowSettings:properties", "-version"],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=15,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r"^\s*java\.home\s*=\s*(.+?)\s*$", completed.stdout or "", re.MULTILINE)
    if match is None:
        return None
    try:
        home = Path(match.group(1).strip()).expanduser().resolve()
    except OSError:
        return None
    return home if java_executable(home).is_file() else None


def _home_from_java_executable(java_exe: Path) -> Path | None:
    try:
        raw = java_exe.expanduser()
        resolved = raw.resolve()
    except OSError:
        resolved = java_exe
    for candidate in (resolved, raw):
        if candidate.parent.name.lower() == "bin":
            home = candidate.parent.parent
            if java_executable(home).is_file():
                return home
    return _reported_java_home(java_exe)


def _windows_registry_java_homes() -> list[Path]:
    if os.name != "nt":
        return []
    try:
        import winreg
    except ImportError:
        return []

    homes: list[Path] = []
    roots = (winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER)
    keys = (
        r"SOFTWARE\JavaSoft\JDK",
        r"SOFTWARE\JavaSoft\Java Development Kit",
        r"SOFTWARE\JavaSoft\JRE",
        r"SOFTWARE\JavaSoft\Java Runtime Environment",
    )
    access_modes = (winreg.KEY_READ | winreg.KEY_WOW64_64KEY, winreg.KEY_READ | winreg.KEY_WOW64_32KEY)
    for hive in roots:
        for key_name in keys:
            for access in access_modes:
                try:
                    with winreg.OpenKey(hive, key_name, 0, access) as root_key:
                        try:
                            current, _ = winreg.QueryValueEx(root_key, "CurrentVersion")
                        except OSError:
                            current = None
                        versions: list[str] = []
                        if current:
                            versions.append(str(current))
                        index = 0
                        while True:
                            try:
                                versions.append(winreg.EnumKey(root_key, index))
                                index += 1
                            except OSError:
                                break
                        for version in dict.fromkeys(versions):
                            try:
                                with winreg.OpenKey(root_key, version) as version_key:
                                    home, _ = winreg.QueryValueEx(version_key, "JavaHome")
                                homes.append(Path(str(home)))
                            except OSError:
                                continue
                except OSError:
                    continue
    return homes


def _bounded_java_scan(root: Path, *, limit: int = 128) -> list[Path]:
    """Scan only known Java-runtime roots, never an arbitrary drive tree."""
    if not root.is_dir():
        return []
    executable_name = "java.exe" if os.name == "nt" else "java"
    results: list[Path] = []
    try:
        for path in root.rglob(executable_name):
            if path.parent.name.lower() != "bin":
                continue
            results.append(path)
            if len(results) >= limit:
                break
    except OSError:
        pass
    return results


def _java_candidates() -> tuple[list[Path], list[Path]]:
    """Return candidate JAVA_HOME directories and java executables in priority order."""
    homes: list[Path] = []
    executables: list[Path] = []

    for key in (
        "JAVA_HOME_17_X64", "JAVA_HOME_21_X64", "JAVA_HOME_17", "JAVA_HOME_21",
        "JDK17_HOME", "JDK21_HOME", "JDK_HOME", "JAVA_HOME",
    ):
        value = os.environ.get(key)
        if value:
            homes.append(Path(value))

    java = shutil.which("java")
    if java:
        executables.append(Path(java))

    if os.name == "nt":
        # `where java` returns every PATH match, while shutil.which only returns the first.
        try:
            found = subprocess.run(
                ("where.exe", "java"), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                text=True, timeout=10, check=False,
            )
            executables.extend(Path(line.strip()) for line in found.stdout.splitlines() if line.strip())
        except (OSError, subprocess.SubprocessError):
            pass

        homes.extend(_windows_registry_java_homes())
        program_roots = [os.environ.get("ProgramFiles"), os.environ.get("ProgramW6432"), os.environ.get("ProgramFiles(x86)")]
        vendor_globs = (
            "Eclipse Adoptium/jdk-*",
            "Java/jdk-*",
            "Microsoft/jdk-*",
            "Amazon Corretto/jdk*",
            "Zulu/zulu-*",
            "BellSoft/LibericaJDK-*",
            "IBM/Semeru/*",
            "JetBrains/*/jbr",
            "JetBrains/*/jbrsdk",
        )
        for base in filter(None, program_roots):
            root = Path(base)
            for pattern in vendor_globs:
                try:
                    homes.extend(root.glob(pattern))
                except OSError:
                    pass

        local = os.environ.get("LOCALAPPDATA")
        roaming = os.environ.get("APPDATA")
        if local:
            local_root = Path(local)
            for pattern in (
                "Programs/Eclipse Adoptium/jdk-*",
                "Programs/Java/jdk-*",
                "Programs/Microsoft/jdk-*",
                "Programs/Amazon Corretto/jdk*",
                "Programs/Zulu/zulu-*",
                "Programs/BellSoft/LibericaJDK-*",
                "JetBrains/*/jbr",
            ):
                try:
                    homes.extend(local_root.glob(pattern))
                except OSError:
                    pass

        # These are the two important locations the previous runner missed:
        # Gradle/Foojay provisioned toolchains and Minecraft Launcher's bundled runtimes.
        managed_roots = [
            Path.home() / ".gradle" / "jdks",
            Path.home() / ".jdks",
            Path.home() / "curseforge" / "minecraft" / "Install" / "runtime",
        ]
        if roaming:
            managed_roots.extend((
                Path(roaming) / ".minecraft" / "runtime",
                Path(roaming) / "PrismLauncher" / "java",
            ))
        if local:
            managed_roots.extend((
                Path(local) / ".minecraft" / "runtime",
                Path(local) / "PrismLauncher" / "java",
            ))
        for root in managed_roots:
            executables.extend(_bounded_java_scan(root))
    else:
        try:
            homes.extend(Path("/usr/lib/jvm").glob("*"))
        except OSError:
            pass
        executables.extend(_bounded_java_scan(Path.home() / ".gradle" / "jdks"))
        executables.extend(_bounded_java_scan(Path.home() / ".jdks"))

    return homes, executables


def discover_java_installations() -> dict[int, list[Path]]:
    """Discover usable JVM homes grouped by their actual reported major version."""
    homes, executables = _java_candidates()
    ordered_homes: list[Path] = []

    for home in homes:
        try:
            home = home.expanduser().resolve()
        except OSError:
            continue
        if java_executable(home).is_file():
            ordered_homes.append(home)

    for executable in executables:
        home = _home_from_java_executable(executable)
        if home is not None:
            ordered_homes.append(home)

    installations: dict[int, list[Path]] = {}
    seen: set[Path] = set()
    for home in ordered_homes:
        try:
            home = home.resolve()
        except OSError:
            continue
        if home in seen:
            continue
        seen.add(home)
        exe = java_executable(home)
        major = java_major(exe) if exe.is_file() else None
        if major is not None:
            installations.setdefault(major, []).append(home)
    return installations


def discover_jdk(major: int, installations: dict[int, list[Path]] | None = None) -> Path | None:
    available = installations if installations is not None else discover_java_installations()
    homes = available.get(major, ())
    return homes[0] if homes else None


def format_java_diagnostics(installations: dict[int, list[Path]]) -> str:
    if not installations:
        return "No working Java runtimes were discovered."
    lines = ["Discovered Java runtimes:"]
    for major in sorted(installations):
        for home in installations[major]:
            lines.append(f"  Java {major}: {home}")
    return "\n".join(lines)

def environment_with_java(base: dict[str, str], java_home: Path) -> dict[str, str]:
    env = dict(base)
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    return env


def require_local_test_jdks(jdks: dict[int, Path], installations: dict[int, list[Path]]) -> None:
    missing = [major for major in (17, 21, 25) if major not in jdks]
    if not missing:
        return
    majors = ", ".join(str(major) for major in missing)
    raise LocalCIError(
        f"Java {majors} is required by the maintained local target matrix, but the local runner could not locate it/them.\n"
        f"{format_java_diagnostics(installations)}\n"
        "Checked environment variables, PATH/where.exe, Windows Java registry entries, common JDK vendors, "
        "%USERPROFILE%\\.gradle\\jdks, %USERPROFILE%\\.jdks, and Minecraft/launcher runtime directories. "
        "Set JAVA_HOME_<major>_X64 for any runtime stored elsewhere."
    )

def gradle_wrapper(build_root: Path) -> Path:
    return build_root / ("gradlew.bat" if os.name == "nt" else "gradlew")


def script_command(path: Path, *args: str) -> tuple[str, ...]:
    if os.name == "nt" and path.suffix.lower() in {".bat", ".cmd"}:
        return ("cmd.exe", "/d", "/c", str(path), *args)
    return (str(path), *args)


def gradle_command(build_root: Path, *args: str) -> tuple[str, ...]:
    return script_command(gradle_wrapper(build_root), *args)


def format_command(command: Sequence[str]) -> str:
    def q(part: str) -> str:
        return part if re.fullmatch(r"[A-Za-z0-9_./:+@=-]+", part) else repr(part)
    return " ".join(q(str(part)) for part in command)


def run_command(
    name: str,
    command: Sequence[str],
    *,
    env: dict[str, str],
    run_dir: Path,
    step_number: int,
    cwd: Path = ROOT,
    append: bool = False,
) -> tuple[int, Path]:
    log_file = run_dir / "steps" / f"{step_number:03d}-{slug(name)}.log"
    log_file.parent.mkdir(parents=True, exist_ok=True)
    mode = "a" if append else "w"
    header = f"==> {name}\n$ {format_command(command)}\nCWD: {cwd}\n\n"
    print(header, end="", flush=True)
    with log_file.open(mode, encoding="utf-8", newline="\n") as log:
        log.write(header)
        process = subprocess.Popen(
            [str(part) for part in command],
            cwd=cwd,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            print(line, end="", flush=True)
            log.write(line)
        status = process.wait()
        trailer = f"\n[exit {status}]\n"
        print(trailer, end="", flush=True)
        log.write(trailer)
    return status, log_file


def copy_artifact_patterns(patterns: Iterable[str], destination: Path) -> int:
    copied = 0
    for pattern in patterns:
        absolute_pattern = str(ROOT / pattern)
        for raw in glob.glob(absolute_pattern, recursive=True):
            source = Path(raw)
            if not source.is_file():
                continue
            try:
                rel = source.relative_to(ROOT)
            except ValueError:
                rel = Path(source.name)
            target = destination / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            copied += 1
    return copied


def build_artifact_patterns(target: str, generation: str) -> tuple[str, ...]:
    return (
        "build/*/*.jar",
        f"builds/{generation}/versions/{target}/build/reports/tests/**",
        f"builds/{generation}/versions/{target}/build/test-results/**",
        f"run/{generation}/{target}/logs/**",
        f"run/{generation}/{target}/crash-reports/**",
    )


def validate_artifact_patterns() -> tuple[str, ...]:
    return (
        "build/reports/source-architecture/**",
    )


def clear_forgegradle_dependency_caches() -> None:
    home = Path.home()
    for path in (
        home / ".gradle/caches/forge_gradle",
        home / ".gradle/caches/modules-2/files-2.1/maven.modrinth",
        home / ".gradle/caches/modules-2/files-2.1/net.minecraftforge",
    ):
        shutil.rmtree(path, ignore_errors=True)
    versions = ROOT / "builds" / "old" / "versions"
    if versions.is_dir():
        for path in versions.rglob("fg_cache"):
            if path.is_dir():
                shutil.rmtree(path, ignore_errors=True)


def write_plan(run_dir: Path, validate_only: bool) -> None:
    lines = ["BuildCraft local test plan", "", "AutoTests / validators:"]
    lines.extend(f"  - {name}" for name, _ in VALIDATE_STEPS)
    if not validate_only:
        lines.append("Target tests:")
        for target, generation, java in TARGETS:
            lines.append(f"  - {target} ({generation}, Java {java}): build + AutoTests -> GameTests -> artifacts")
    (run_dir / "plan.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))

def main() -> int:
    args = parse_args()
    os.chdir(ROOT)
    workflow_alignment_check()

    run_id = args.run_id or dt.datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    run_dir = LOG_ROOT / slug(run_id)
    run_dir.mkdir(parents=True, exist_ok=True)
    write_plan(run_dir, args.validate_only)
    (LOG_ROOT / "latest.txt").write_text(str(run_dir.relative_to(ROOT)) + "\n", encoding="utf-8")

    if args.dry_run:
        print(f"\nDry run only. Plan saved to {run_dir.relative_to(ROOT) / 'plan.txt'}")
        return 0

    if sys.version_info < (3, 11):
        raise LocalCIError(f"Python 3.11+ is required; running {sys.version.split()[0]}")

    installations = discover_java_installations()
    jdks: dict[int, Path] = {}
    for major in (17, 21, 25):
        found = discover_jdk(major, installations)
        if found is not None:
            jdks[major] = found

    print("\nJava runtime discovery:")
    print(format_java_diagnostics(installations))
    for major in (17, 21, 25):
        if major in jdks:
            print(f"Selected Java {major}: {jdks[major]}")

    if 21 not in jdks:
        raise LocalCIError(
            "Java 21 is required for the validate job, but the local runner could not locate it. "
            "This does not mean Java is absent from the machine.\n"
            + format_java_diagnostics(installations)
            + "\nIf Java 21 lives in a custom location, set JAVA_HOME_21_X64 to that runtime home."
        )
    if not args.validate_only:
        require_local_test_jdks(jdks, installations)

    base_env = dict(os.environ)
    # GitHub Actions runs on Linux, where Python's default text encoding is UTF-8.
    # Windows may otherwise inherit a legacy locale such as cp1251, causing tests
    # that intentionally rely on Python's platform default (Path.read_text()) to
    # fail locally before their assertions run. Force child Python processes to
    # use the same UTF-8 semantics as CI, and keep redirected stdout/stderr UTF-8
    # so this runner can decode every transcript deterministically.
    base_env["PYTHONUTF8"] = "1"
    base_env["PYTHONIOENCODING"] = "utf-8"
    base_env["GRADLE_OPTS"] = "-Dorg.gradle.daemon=false -Dfile.encoding=UTF-8"
    for major, home in jdks.items():
        base_env[f"JAVA_HOME_{major}_X64"] = str(home)
    validation_env = environment_with_java(base_env, jdks[21])

    summary: dict[str, object] = {
        "run_id": run_id,
        "workflow": str(WORKFLOW.relative_to(ROOT)),
        "validate_only": args.validate_only,
        "started": dt.datetime.now().astimezone().isoformat(),
        "steps": [],
    }
    step_number = 0

    def record(name: str, status: int, log: Path | None, *, skipped: bool = False) -> None:
        cast_steps = summary["steps"]
        assert isinstance(cast_steps, list)
        cast_steps.append({
            "name": name,
            "status": status,
            "skipped": skipped,
            "log": str(log.relative_to(ROOT)) if log else None,
        })
        (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")

    # GitHub validate job, exact declared step order.
    for name, command in VALIDATE_STEPS:
        step_number += 1
        if command == ("__ARCHITECTURE__",):
            actual = [
                sys.executable,
                "scripts/validate-architecture-hardening.py",
                "--json", "build/reports/source-architecture/architecture-hardening.json",
                "--markdown", "build/reports/source-architecture/architecture-hardening.md",
            ]
            base_sha = base_env.get("BASE_SHA", "").strip()
            if base_sha:
                actual.extend(("--budget-ref", base_sha))
            status, log = run_command(name, actual, env=validation_env, run_dir=run_dir, step_number=step_number)
            if status == 0:
                report = ROOT / "build/reports/source-architecture/architecture-hardening.md"
                if report.is_file():
                    with log.open("a", encoding="utf-8") as fh:
                        fh.write("\n--- architecture-hardening.md ---\n")
                        fh.write(report.read_text(encoding="utf-8"))
                        fh.write("\n")
        else:
            status, log = run_command(name, command, env=validation_env, run_dir=run_dir, step_number=step_number)
        record(name, status, log)
        if status != 0:
            validate_destination = run_dir / "artifacts" / "validate"
            copy_artifact_patterns(validate_artifact_patterns(), validate_destination)
            summary["finished"] = dt.datetime.now().astimezone().isoformat()
            summary["result"] = "failed"
            (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
            print(f"\nCI stopped at failed validate step: {name}")
            print(f"Logs: {run_dir.relative_to(ROOT)}")
            return status

    validate_destination = run_dir / "artifacts" / "validate"
    validate_copied = copy_artifact_patterns(validate_artifact_patterns(), validate_destination)
    if validate_copied:
        print(f"Collected {validate_copied} validate artifact file(s) -> {validate_destination.relative_to(ROOT)}")

    if args.validate_only:
        summary["finished"] = dt.datetime.now().astimezone().isoformat()
        summary["result"] = "success"
        (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(f"\nValidate job passed. Logs: {run_dir.relative_to(ROOT)}")
        return 0

    # Target matrix. Local runs stop at AutoTests/build + GameTests; runtime client/server smoke stays in GitHub CI.
    for target, generation, java in TARGETS:
        target_env = environment_with_java(base_env, jdks[java])
        target_env["BUILD_GENERATION"] = generation
        target_env["STONECUTTER_TARGET"] = target
        build_root = ROOT / "builds" / generation
        gradlew = gradle_wrapper(build_root)
        try:
            gradlew.chmod(gradlew.stat().st_mode | 0o111)
        except OSError:
            pass
        try:
            (ROOT / "scripts/source_layout.py").chmod((ROOT / "scripts/source_layout.py").stat().st_mode | 0o111)
        except OSError:
            pass

        if target == "1.20.1-forge":
            step_number += 1
            name = f"Validate Forge 1.20.1 target invariants [{target}]"
            status, log = run_command(
                name,
                (sys.executable, "scripts/validate-1.20.1-target.py", "--source-root", "version-src/1.20.1-forge"),
                env=target_env,
                run_dir=run_dir,
                step_number=step_number,
            )
            record(name, status, log)
            if status != 0:
                return status

        step_number += 1
        name = f"Run AutoTests and build target [{target}]"
        build_command = gradle_command(
            build_root, "--no-daemon", "--console=plain", "--stacktrace", f":{target}:buildAndCollect"
        )
        status, log = run_command(name, build_command, env=target_env, run_dir=run_dir, step_number=step_number, cwd=build_root)
        if status != 0 and generation == "old":
            content = log.read_text(encoding="utf-8", errors="replace")
            if re.search(r"ZipException|invalid LOC header|zip END header not found", content):
                print(f"ForgeGradle cache corruption detected for {target}; clearing dependency caches and retrying once.")
                clear_forgegradle_dependency_caches()
                retry_command = gradle_command(
                    build_root, "--no-daemon", "--no-parallel", "--console=plain", "--stacktrace",
                    "--refresh-dependencies", f":{target}:buildAndCollect",
                )
                status, log = run_command(
                    name + " [retry]", retry_command, env=target_env, run_dir=run_dir,
                    step_number=step_number, cwd=build_root, append=True,
                )
        record(name, status, log)
        if status != 0:
            destination = run_dir / "artifacts" / f"buildcraft-{target}"
            copy_artifact_patterns(build_artifact_patterns(target, generation), destination)
            return status

        step_number += 1
        name = f"Run GameTests [{target}]"
        status, log = run_command(
            name,
            gradle_command(build_root, "--no-daemon", "--console=plain", "--stacktrace", f":{target}:runGameTestServer"),
            env=target_env,
            run_dir=run_dir,
            step_number=step_number,
            cwd=build_root,
        )
        record(name, status, log)

        destination = run_dir / "artifacts" / f"buildcraft-{target}"
        copied = copy_artifact_patterns(build_artifact_patterns(target, generation), destination)
        print(f"Collected {copied} artifact file(s) for {target} -> {destination.relative_to(ROOT)}")
        if status != 0:
            return status

    summary["finished"] = dt.datetime.now().astimezone().isoformat()
    summary["result"] = "success"
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"\nLocal AutoTests and GameTests passed. Logs and artifacts: {run_dir.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except LocalCIError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
