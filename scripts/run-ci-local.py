#!/usr/bin/env python3
"""Run the GitHub CI acceptance gates locally, in workflow order.

The default mode mirrors .github/workflows/ci.yml as closely as a single local
machine can: validate first, then every build/test/server-smoke matrix entry in
declared order, then compatibility profiles. The GitHub-only client smoke step is
intentionally skipped locally so local CI never launches a Minecraft client.
Every step transcript and runtime artifact is copied under logs/ci-local/<run-id>/.

On Linux the runner invokes the exact CI server-smoke shell script. On Windows it
runs a native equivalent of that launch/monitor wrapper with the same Gradle task,
profile, readiness/fatal checks and artifacts.
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
import time
import urllib.error
import urllib.request
import hashlib
import signal
from typing import Iterable, Sequence

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "ci.yml"
LOG_ROOT = ROOT / "logs" / "ci-local"
TARGETS = (
    ("1.19.2-forge", "legacy", 17),
    ("1.20.1-forge", "legacy", 17),
    ("1.20.1-fabric", "legacy", 17),
    ("1.21.1-neoforge", "modern", 21),
    ("1.21.11-neoforge", "modern", 21),
)
SERVER_FOUNDATION_TARGETS = {"1.20.1-fabric"}
COMPATIBILITY = (
    ("1.19.2-forge", "forestry"),
    ("1.19.2-forge", "ic2"),
    ("1.20.1-forge", "forestry"),
)
VALIDATE_STEPS: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("Validate independent Stonecutter builds", (sys.executable, "scripts/validate-stonecutter.py")),
    ("Validate hybrid source layout", (sys.executable, "scripts/validate-source-families.py")),
    # Architecture hardening is assembled at runtime because BASE_SHA is optional in the workflow.
    ("Enforce architecture budgets and trend", ("__ARCHITECTURE__",)),
    ("Validate API v2 boundary", (sys.executable, "scripts/validate-api-v2.py")),
    ("Validate API v2-only public surface", (sys.executable, "scripts/validate-api-v2-only.py")),
    ("Validate API v2 symbol baseline", (sys.executable, "scripts/validate-api-v2-symbol-baseline.py")),
    ("Validate API v2 coverage matrix", (sys.executable, "scripts/validate-api-v2-coverage.py")),
    ("Validate API2 runtime completeness", (sys.executable, "scripts/validate-api2-runtime-completeness.py")),
    ("Validate API2 module contracts", ("__API2_MODULE_CONTRACTS__",)),
    ("Validate repository cleanliness", (sys.executable, "scripts/validate-repository-cleanliness.py")),
    ("Validate cross-version gameplay parity", (sys.executable, "scripts/validate-behavior-parity.py")),
    ("Validate 1.21.11 parity", (sys.executable, "scripts/validate-12111-parity.py")),
    ("Validate Zone Planner block preview parity", (sys.executable, "scripts/validate-zone-planner-preview.py")),
    ("Validate FE compatibility", (sys.executable, "scripts/validate-fe-compat.py")),
    ("Validate FE Engine and MJ Dynamo parity", (sys.executable, "scripts/validate-fe-mj-engine-parity.py")),
    ("Validate JEI crafting layout parity", (sys.executable, "scripts/validate-jei-crafting-layouts.py")),
    ("Validate gameplay, render and performance regressions", (sys.executable, "scripts/validate-regressions.py")),
    ("Validate guidebook runtime claims", (sys.executable, "scripts/validate-guide-runtime-claims.py")),
    ("Test internal Minecraft compatibility boundaries", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_minecraft_compat.py", "-v")),
    ("Test actor tickets and client registration boundaries", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_actor_client_boundaries.py", "-v")),
    ("Test loader-neutral packet boundaries", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_loader_boundaries.py", "-v")),
    ("Test Fabric runtime preparation boundaries", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_runtime_boundaries.py", "-v")),
    ("Test Fabric loader target bootstrap", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_fabric_loader_target.py", "-v")),
    ("Test Fabric server foundation", (sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-p", "test_fabric_server_foundation.py", "-v")),
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
API2_MODULE_SCRIPTS = (
    "scripts/validate-core-misc-api2.py",
    "scripts/validate-energy-api2.py",
    "scripts/validate-facades-lists-map-api2.py",
    "scripts/validate-robots-api2.py",
    "scripts/validate-schematics-api2.py",
    "scripts/validate-signals-automation-api2.py",
    "scripts/validate-statements-api2.py",
    "scripts/validate-transport-api2.py",
)

SERVER_READY_REGEX = r'Done \([0-9.,]+s\)! For help, type "help"|Done \([0-9.,]+s\)!'
SERVER_FATAL_REGEX = (
    r'Failed to start the minecraft server|Exception in server tick loop|Loading errors encountered|'
    r'Mod loading has failed|A fatal error has been detected|ResolutionException|'
    r'UnsupportedClassVersionError'
)

class LocalCIError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="Run only the validate job. Default is the complete CI sequence.",
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


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def workflow_alignment_check() -> None:
    """Fail closed when the workflow order changes so this runner cannot silently become stale."""
    text = WORKFLOW.read_text(encoding="utf-8")
    validate_start = text.find("  validate:")
    build_start = text.find("  build-test-server:")
    compat_start = text.find("  Compatibility:")
    if min(validate_start, build_start, compat_start) < 0 or not (validate_start < build_start < compat_start):
        raise LocalCIError("Unable to identify validate/build/Compatibility jobs in .github/workflows/ci.yml")

    validate_text = text[validate_start:build_start]
    position = 0
    for name, _ in VALIDATE_STEPS:
        token = f"- name: {name}"
        found = validate_text.find(token, position)
        if found < 0:
            raise LocalCIError(f"Local CI runner is stale: workflow step missing/out of order: {name}")
        position = found + len(token)

    # Guard command-level drift for the compound steps that are easiest to accidentally desynchronise.
    required_validate_fragments = (
        "python scripts/validate-architecture-hardening.py",
        *[f"python {script}" for script in API2_MODULE_SCRIPTS],
        "python scripts/validate-cross-target-integrity.py",
    )
    for fragment in required_validate_fragments:
        if fragment not in validate_text:
            raise LocalCIError(f"Local CI runner is stale: workflow no longer contains {fragment!r}")

    build_text = text[build_start:compat_start]
    build_step_names = (
        "Validate Forge 1.20.1 port invariants",
        "Build and test target",
        "Run GameTests",
        "Smoke-test production jar",
        "Client smoke-test production target",
        "Upload build products and diagnostics",
    )
    position = 0
    for name in build_step_names:
        found = build_text.find(f"- name: {name}", position)
        if found < 0:
            raise LocalCIError(f"Local CI runner is stale: build step missing/out of order: {name}")
        position = found + len(name)
    for target, generation, java in TARGETS:
        block = f"- target: {target}\n            generation: {generation}\n            java: '{java}'"
        if block not in build_text:
            raise LocalCIError(f"Local CI runner is stale: build matrix entry changed for {target}")
    for fragment in (
        '":${STONECUTTER_TARGET}:buildAndCollect"',
        '":${STONECUTTER_TARGET}:runGameTestServer"',
        "bash scripts/ci-server-smoke.sh",
        "if: matrix.foundation != true",
    ):
        if fragment not in build_text:
            raise LocalCIError(f"Local CI runner is stale: build command changed: {fragment}")

    compat_text = text[compat_start:]
    position = 0
    for target, profile in COMPATIBILITY:
        block = f"- target: {target}\n            profile: {profile}"
        found = compat_text.find(block, position)
        if found < 0:
            raise LocalCIError(f"Local CI runner is stale: compatibility matrix changed at {target}/{profile}")
        position = found + len(block)

    # Windows uses a native server launch/process wrapper instead of setsid. Keep its
    # readiness and failure semantics locked to the shell script GitHub actually runs.
    smoke_contracts = (
        (ROOT / "scripts" / "ci-server-smoke.sh", "success_regex", SERVER_READY_REGEX),
        (ROOT / "scripts" / "ci-server-smoke.sh", "fatal_regex", SERVER_FATAL_REGEX),
    )
    for script, variable, expected in smoke_contracts:
        script_text = script.read_text(encoding="utf-8")
        match = re.search(rf"^{re.escape(variable)}='(.*)'$", script_text, re.MULTILINE)
        if match is None or match.group(1) != expected:
            raise LocalCIError(
                f"Local CI runner is stale: {script.relative_to(ROOT)} {variable} no longer matches the native runner"
            )


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


def require_full_ci_tools(jdks: dict[int, Path], installations: dict[int, list[Path]]) -> None:
    if os.name != "nt":
        missing = [name for name in ("bash", "curl", "setsid", "xvfb-run") if shutil.which(name) is None]
        if missing:
            raise LocalCIError(
                "Missing tools required by the same smoke tests used in GitHub CI: " + ", ".join(missing)
                + ". On Debian/Ubuntu install xvfb, libgl1-mesa-dri and libglx-mesa0 as CI does."
            )
    for major in (17, 21):
        if major not in jdks:
            raise LocalCIError(
                f"Java {major} is required by the GitHub CI target matrix, but the local runner could not locate "
                f"a Java {major} runtime. This does not mean Java is absent from the machine.\n"
                f"{format_java_diagnostics(installations)}\n"
                f"Checked environment variables, PATH/where.exe, Windows Java registry entries, common JDK vendors, "
                f"%USERPROFILE%\\.gradle\\jdks, %USERPROFILE%\\.jdks, and Minecraft/launcher runtime directories. "
                f"If the runtime lives elsewhere, set JAVA_HOME_{major}_X64 to its home directory."
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


def _step_log_path(run_dir: Path, step_number: int, name: str) -> Path:
    path = run_dir / "steps" / f"{step_number:03d}-{slug(name)}.log"
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def _emit(log, message: str = "") -> None:
    print(message, flush=True)
    log.write(message + "\n")
    log.flush()


def _tail_text(path: Path, max_bytes: int = 2_000_000) -> str:
    if not path.is_file():
        return ""
    try:
        with path.open("rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - max_bytes), os.SEEK_SET)
            return fh.read().decode("utf-8", errors="replace")
    except OSError:
        return ""


def _combined_logs(*paths: Path) -> str:
    return "\n".join(_tail_text(path) for path in paths if path.is_file())


def _show_log_tail(log, path: Path, lines: int = 200) -> None:
    text = _tail_text(path)
    if not text:
        return
    _emit(log, f"--- {path} (last {lines} lines) ---")
    for line in text.splitlines()[-lines:]:
        _emit(log, line)


def _start_background(command: Sequence[str], *, cwd: Path, env: dict[str, str], output_path: Path):
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output = output_path.open("w", encoding="utf-8", newline="\n")
    kwargs: dict[str, object] = {}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    try:
        process = subprocess.Popen(
            [str(part) for part in command],
            cwd=cwd,
            env=env,
            stdout=output,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            **kwargs,
        )
    except Exception:
        output.close()
        raise
    return process, output


def _stop_process_tree(process: subprocess.Popen) -> None:
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(
            ("taskkill", "/PID", str(process.pid), "/T", "/F"),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
        return
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            process.kill()


def _target_property(target_props: dict[str, str], common_props: dict[str, str], target: str, suffix: str) -> str:
    return target_props.get(f"target.{target}.{suffix}", common_props.get(f"common.{suffix}", ""))


def _download_file(url: str, destination: Path, log, attempts: int = 3) -> None:
    """Download a loader installer with CI-equivalent curl semantics.

    GitHub Actions uses curl directly in ci-server-smoke.sh.  Prefer the same
    executable on Windows instead of Python urllib, because some Maven/CDN
    frontends reject urllib's default request fingerprint with HTTP 403.
    Successful downloads are cached below logs/ci-local/cache so repeated local
    CI runs do not needlessly fetch the same immutable installer.
    """
    destination.parent.mkdir(parents=True, exist_ok=True)
    cache_dir = LOG_ROOT / "cache" / "loader-installers"
    cache_dir.mkdir(parents=True, exist_ok=True)
    suffix = Path(url.split("?", 1)[0]).name or "loader-installer.jar"
    cache_key = hashlib.sha256(url.encode("utf-8")).hexdigest()[:16]
    cached = cache_dir / f"{cache_key}-{suffix}"
    if cached.is_file() and cached.stat().st_size > 0:
        _emit(log, f"Using cached loader installer: {cached.relative_to(ROOT)}")
        shutil.copy2(cached, destination)
        return

    errors: list[str] = []

    # Match scripts/ci-server-smoke.sh first: curl --fail --location --silent
    # --show-error --retry 3 --retry-delay 2. Windows 10/11 ships curl.exe.
    curl = shutil.which("curl.exe") if os.name == "nt" else shutil.which("curl")
    if curl:
        destination.unlink(missing_ok=True)
        _emit(log, f"Downloading loader installer with curl (CI-equivalent): {url}")
        completed = subprocess.run(
            (
                curl,
                "--fail",
                "--location",
                "--silent",
                "--show-error",
                "--retry",
                str(attempts),
                "--retry-delay",
                "2",
                "--output",
                str(destination),
                url,
            ),
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            check=False,
        )
        if completed.returncode == 0 and destination.is_file() and destination.stat().st_size > 0:
            shutil.copy2(destination, cached)
            return
        errors.append(f"curl exit {completed.returncode}: {(completed.stdout or '').strip()}")
        destination.unlink(missing_ok=True)

    # Native PowerShell is a useful fallback on machines where curl.exe was
    # removed or shadowed. Give the request a normal CLI user agent.
    if os.name == "nt":
        powershell = shutil.which("powershell.exe") or shutil.which("pwsh.exe")
        if powershell:
            for attempt in range(1, attempts + 1):
                destination.unlink(missing_ok=True)
                _emit(log, f"Downloading loader installer with PowerShell ({attempt}/{attempts}): {url}")
                ps_url = "'" + url.replace("'", "''") + "'"
                ps_destination = "'" + str(destination).replace("'", "''") + "'"
                command = (
                    powershell,
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    (
                        "$ProgressPreference='SilentlyContinue'; "
                        "Invoke-WebRequest -UseBasicParsing "
                        "-UserAgent 'Mozilla/5.0 BuildCraft-Local-CI' "
                        f"-Uri {ps_url} -OutFile {ps_destination}"
                    ),
                )
                completed = subprocess.run(
                    command,
                    cwd=ROOT,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    check=False,
                )
                if completed.returncode == 0 and destination.is_file() and destination.stat().st_size > 0:
                    shutil.copy2(destination, cached)
                    return
                errors.append(
                    f"PowerShell attempt {attempt} exit {completed.returncode}: {(completed.stdout or '').strip()}"
                )
                if attempt < attempts:
                    time.sleep(2)

    # Last resort for unusual environments. Explicit headers avoid urllib's
    # default Python-urllib user agent, which is rejected by some Maven CDNs.
    last_error: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            destination.unlink(missing_ok=True)
            _emit(log, f"Downloading loader installer with urllib fallback ({attempt}/{attempts}): {url}")
            request = urllib.request.Request(
                url,
                headers={
                    "User-Agent": "Mozilla/5.0 BuildCraft-Local-CI",
                    "Accept": "*/*",
                },
            )
            with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as output:
                shutil.copyfileobj(response, output)
            if destination.stat().st_size <= 0:
                raise OSError("downloaded file is empty")
            shutil.copy2(destination, cached)
            return
        except (OSError, urllib.error.URLError) as exc:
            last_error = exc
            errors.append(f"urllib attempt {attempt}: {exc}")
            destination.unlink(missing_ok=True)
            if attempt < attempts:
                time.sleep(2)

    detail = "; ".join(error for error in errors if error)
    raise LocalCIError(
        f"Unable to download loader installer after curl/PowerShell/urllib attempts: {detail or last_error}"
    )


def _monitor_server(
    process: subprocess.Popen,
    *,
    log,
    server_log: Path,
    latest_log: Path,
    timeout: int,
    expected_version: str,
) -> int:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        combined = _combined_logs(server_log, latest_log)
        if re.search(SERVER_READY_REGEX, combined, re.IGNORECASE):
            if re.search(r"Starting BuildCraft\s+(\$version|\$\{|.*\$\{)", combined):
                _emit(log, "Dedicated server started with unresolved BuildCraft Java build metadata.")
                _show_log_tail(log, server_log)
                _show_log_tail(log, latest_log)
                return 1
            if f"Starting BuildCraft {expected_version}" not in combined:
                _emit(log, f"Dedicated server did not report expected BuildCraft version {expected_version}.")
                _show_log_tail(log, server_log)
                _show_log_tail(log, latest_log)
                return 1
            _emit(log, f"Dedicated server reached the ready state successfully with BuildCraft {expected_version}.")
            return 0
        if re.search(SERVER_FATAL_REGEX, combined, re.IGNORECASE):
            _emit(log, "Dedicated server reported a fatal startup error.")
            _show_log_tail(log, server_log)
            _show_log_tail(log, latest_log)
            return 1
        status = process.poll()
        if status is not None:
            _emit(log, f"Dedicated server process exited before becoming ready (status {status}).")
            _show_log_tail(log, server_log)
            _show_log_tail(log, latest_log)
            return 1
        time.sleep(5)
    _emit(log, f"Dedicated server did not reach the ready state within {timeout}s.")
    _show_log_tail(log, server_log)
    _show_log_tail(log, latest_log)
    return 1


def run_native_server_smoke(
    name: str,
    *,
    target: str,
    generation: str,
    profile: str,
    java_home: Path,
    env: dict[str, str],
    run_dir: Path,
    step_number: int,
) -> tuple[int, Path]:
    step_log = _step_log_path(run_dir, step_number, name)
    target_props = read_properties(ROOT / "build-config" / "targets.properties")
    common_props = read_properties(ROOT / "build-config" / "common.properties")
    mod_version = common_props.get("common.mod.version", "")
    expected_version = f"{mod_version}+{target.replace('-', '+')}"
    timeout = int(env.get("SERVER_STARTUP_TIMEOUT", "360"))
    build_root = ROOT / "builds" / generation
    runtime_log_dir = run_dir / "runtime"
    runtime_log_dir.mkdir(parents=True, exist_ok=True)
    server_log = runtime_log_dir / f"ci-server-{generation}-{target}-{profile}.log"

    with step_log.open("w", encoding="utf-8", newline="\n") as log:
        _emit(log, f"==> {name}")
        _emit(log, "Native Windows wrapper for scripts/ci-server-smoke.sh; readiness/fatal checks and runtime profile match CI.")
        if not mod_version:
            _emit(log, "Missing BuildCraft mod version metadata.")
            return 2, step_log

        if profile == "base":
            minecraft = _target_property(target_props, common_props, target, "deps.minecraft")
            loader = target.rsplit("-", 1)[-1]
            if loader == "forge":
                loader_version = _target_property(target_props, common_props, target, "deps.forge")
                installer_url = (
                    f"https://maven.minecraftforge.net/net/minecraftforge/forge/{minecraft}-{loader_version}/"
                    f"forge-{minecraft}-{loader_version}-installer.jar"
                )
            elif loader == "neoforge":
                loader_version = _target_property(target_props, common_props, target, "deps.neoforge")
                installer_url = (
                    f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{loader_version}/"
                    f"neoforge-{loader_version}-installer.jar"
                )
            else:
                _emit(log, f"Installed-server smoke is not implemented for loader {loader!r}.")
                return 2, step_log

            jar_dir = build_root / "versions" / target / "build" / "libs"
            jars = sorted(
                p for p in jar_dir.glob("*.jar")
                if not p.name.endswith("-sources.jar") and not p.name.endswith("-javadoc.jar")
            ) if jar_dir.is_dir() else []
            if len(jars) != 1:
                _emit(log, f"Expected exactly one production jar in {jar_dir}, found {len(jars)}.")
                for jar in jars:
                    _emit(log, f"  {jar}")
                return 2, step_log

            server_dir = ROOT / "run-server" / generation / target
            install_log = runtime_log_dir / f"ci-server-install-{generation}-{target}.log"
            latest_log = server_dir / "logs" / "latest.log"
            shutil.rmtree(server_dir, ignore_errors=True)
            server_dir.mkdir(parents=True, exist_ok=True)
            installer = server_dir / "loader-installer.jar"
            try:
                _download_file(installer_url, installer, log)
            except LocalCIError as exc:
                _emit(log, f"Dedicated server installation download failed: {exc}")
                return 1, step_log

            java_bin = java_executable(java_home)
            with install_log.open("w", encoding="utf-8", newline="\n") as install_out:
                install = subprocess.run(
                    (str(java_bin), "-jar", str(installer), "--installServer"),
                    cwd=server_dir,
                    env=env,
                    stdout=install_out,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    check=False,
                )
            installer.unlink(missing_ok=True)
            if install.returncode != 0:
                _emit(log, f"Dedicated server installation failed (exit {install.returncode}).")
                _show_log_tail(log, install_log)
                return 1, step_log

            run_file = server_dir / ("run.bat" if os.name == "nt" else "run.sh")
            if not run_file.is_file():
                fallback = server_dir / ("run.sh" if run_file.name == "run.bat" else "run.bat")
                run_file = fallback if fallback.is_file() else run_file
            if not run_file.is_file():
                _emit(log, f"Loader installer completed without creating a launch script in {server_dir}.")
                _show_log_tail(log, install_log)
                return 1, step_log

            mods = server_dir / "mods"
            mods.mkdir(parents=True, exist_ok=True)
            shutil.copy2(jars[0], mods / jars[0].name)
            (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
            (server_dir / "server.properties").write_text(
                "online-mode=false\nserver-ip=127.0.0.1\nserver-port=25565\nlevel-name=ci-world\n"
                "motd=BuildCraft production jar CI smoke test\nenable-command-block=false\nspawn-protection=0\nmax-tick-time=-1\n",
                encoding="utf-8",
            )
            (server_dir / "user_jvm_args.txt").write_text(
                "-Xms256M\n-Xmx2G\n-Dfile.encoding=UTF-8\n", encoding="utf-8"
            )
            command = script_command(run_file, "nogui")
            cwd = server_dir
            _emit(log, f"Starting installed {loader} server for {minecraft} ({target}, timeout {timeout}s).")
        else:
            server_dir = ROOT / "run" / generation / target
            latest_log = server_dir / "logs" / "latest.log"
            server_dir.mkdir(parents=True, exist_ok=True)
            latest_log.unlink(missing_ok=True)
            (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
            (server_dir / "server.properties").write_text(
                "online-mode=false\nserver-ip=127.0.0.1\nserver-port=25565\nlevel-name=ci-world\n"
                "motd=BuildCraft compatibility CI server smoke test\nenable-command-block=false\nspawn-protection=0\nmax-tick-time=-1\n",
                encoding="utf-8",
            )
            command = gradle_command(
                build_root,
                "--no-daemon", "--console=plain", "--stacktrace",
                f"-Pci_runtime_profile={profile}", f":{target}:runServer",
            )
            cwd = build_root
            _emit(log, f"Starting compatibility userdev server ({target}/{profile}, timeout {timeout}s).")

        server_log.unlink(missing_ok=True)
        process, output = _start_background(command, cwd=cwd, env=env, output_path=server_log)
        try:
            status = _monitor_server(
                process,
                log=log,
                server_log=server_log,
                latest_log=latest_log,
                timeout=timeout,
                expected_version=expected_version,
            )
        finally:
            _stop_process_tree(process)
            output.close()
        _emit(log, f"[exit {status}]")
        return status, step_log


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
        f"run-server/{generation}/{target}/logs/**",
        f"run-server/{generation}/{target}/crash-reports/**",
    )


def compatibility_artifact_patterns(target: str, profile: str) -> tuple[str, ...]:
    return (
        f"run/legacy/{target}/logs/**",
        f"run/legacy/{target}/crash-reports/**",
    )


def copy_runtime_logs(run_dir: Path, destination: Path, patterns: Iterable[str]) -> int:
    source_root = run_dir / "runtime"
    if not source_root.is_dir():
        return 0
    target_root = destination / "ci-logs"
    copied = 0
    seen: set[Path] = set()
    for pattern in patterns:
        for source in source_root.glob(pattern):
            if not source.is_file() or source in seen:
                continue
            seen.add(source)
            target_root.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target_root / source.name)
            copied += 1
    return copied


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
    versions = ROOT / "builds" / "legacy" / "versions"
    if versions.is_dir():
        for path in versions.rglob("fg_cache"):
            if path.is_dir():
                shutil.rmtree(path, ignore_errors=True)


def write_plan(run_dir: Path, validate_only: bool) -> None:
    lines = ["BuildCraft local CI plan", "", "validate:"]
    lines.extend(f"  - {name}" for name, _ in VALIDATE_STEPS)
    if not validate_only:
        lines.append("build-test-server (sequential local form of CI matrix):")
        for target, generation, java in TARGETS:
            if target in SERVER_FOUNDATION_TARGETS:
                lines.append(f"  - {target} ({generation}, Java {java}): server-foundation build -> server smoke -> artifacts")
            else:
                lines.append(f"  - {target} ({generation}, Java {java}): build -> GameTests -> server smoke -> artifacts")
        lines.append("Compatibility:")
        for target, profile in COMPATIBILITY:
            lines.append(f"  - {target} / {profile}: server smoke -> artifacts")
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
    for major in (17, 21):
        found = discover_jdk(major, installations)
        if found is not None:
            jdks[major] = found

    print("\nJava runtime discovery:")
    print(format_java_diagnostics(installations))
    for major in (17, 21):
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
        require_full_ci_tools(jdks, installations)

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
        elif command == ("__API2_MODULE_CONTRACTS__",):
            log = None
            status = 0
            for index, script in enumerate(API2_MODULE_SCRIPTS):
                sub_name = name if index == 0 else f"{name} ({Path(script).stem})"
                current_status, current_log = run_command(
                    sub_name,
                    (sys.executable, script),
                    env=validation_env,
                    run_dir=run_dir,
                    step_number=step_number,
                    append=index > 0,
                )
                log = current_log
                if current_status != 0:
                    status = current_status
                    break
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

    # Build/test/server-smoke matrix. GitHub executes these entries in parallel; locally we use the declaration
    # order above so results are deterministic and readable.
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
        for script in (ROOT / "build-all.sh", ROOT / "scripts/ci-server-smoke.sh", ROOT / "scripts/source_layout.py"):
            try:
                script.chmod(script.stat().st_mode | 0o111)
            except OSError:
                pass

        if target == "1.20.1-forge":
            step_number += 1
            name = f"Validate Forge 1.20.1 port invariants [{target}]"
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
        name = f"Build and test target [{target}]"
        build_command = gradle_command(
            build_root, "--no-daemon", "--console=plain", "--stacktrace", f":{target}:buildAndCollect"
        )
        status, log = run_command(name, build_command, env=target_env, run_dir=run_dir, step_number=step_number, cwd=build_root)
        if status != 0 and target.endswith("-forge"):
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

        if target in SERVER_FOUNDATION_TARGETS:
            game_name = f"Run GameTests [{target}]"
            record(game_name, 0, None, skipped=True)
            step_number += 1
            smoke_name = f"Smoke-test production jar [{target}]"
            server_env = dict(target_env)
            server_env["SERVER_STARTUP_TIMEOUT"] = "360"
            server_env["SERVER_RUNTIME_PROFILE"] = "base"
            runtime_log_dir = run_dir / "runtime"
            runtime_log_dir.mkdir(parents=True, exist_ok=True)
            server_env["SERVER_LOG_FILE"] = str(runtime_log_dir / f"ci-server-{generation}-{target}-base.log")
            server_env["SERVER_INSTALL_LOG_FILE"] = str(runtime_log_dir / f"ci-server-install-{generation}-{target}.log")
            if os.name == "nt":
                status, log = run_native_server_smoke(
                    smoke_name, target=target, generation=generation, profile="base", java_home=jdks[java],
                    env=server_env, run_dir=run_dir, step_number=step_number,
                )
            else:
                status, log = run_command(
                    smoke_name, ("bash", "scripts/ci-server-smoke.sh"), env=server_env,
                    run_dir=run_dir, step_number=step_number,
                )
            record(smoke_name, status, log)
            destination = run_dir / "artifacts" / f"buildcraft-{target}"
            copied = copy_artifact_patterns(build_artifact_patterns(target, generation), destination)
            copied += copy_runtime_logs(
                run_dir, destination,
                (f"ci-server-{generation}-{target}-*.log", f"ci-server-install-{generation}-{target}.log"),
            )
            print(f"Collected {copied} server-foundation artifact file(s) for {target} -> {destination.relative_to(ROOT)}")
            if status != 0:
                return status
            continue

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
        if status == 0:
            step_number += 1
            name = f"Smoke-test production jar [{target}]"
            server_env = dict(target_env)
            server_env["SERVER_STARTUP_TIMEOUT"] = "360"
            server_env["SERVER_RUNTIME_PROFILE"] = "base"
            runtime_log_dir = run_dir / "runtime"
            runtime_log_dir.mkdir(parents=True, exist_ok=True)
            server_env["SERVER_LOG_FILE"] = str(runtime_log_dir / f"ci-server-{generation}-{target}-base.log")
            server_env["SERVER_INSTALL_LOG_FILE"] = str(runtime_log_dir / f"ci-server-install-{generation}-{target}.log")
            if os.name == "nt":
                status, log = run_native_server_smoke(
                    name, target=target, generation=generation, profile="base", java_home=jdks[java],
                    env=server_env, run_dir=run_dir, step_number=step_number,
                )
            else:
                status, log = run_command(
                    name, ("bash", "scripts/ci-server-smoke.sh"), env=server_env,
                    run_dir=run_dir, step_number=step_number,
                )
            record(name, status, log)

        destination = run_dir / "artifacts" / f"buildcraft-{target}"
        copied = copy_artifact_patterns(build_artifact_patterns(target, generation), destination)
        copied += copy_runtime_logs(
            run_dir,
            destination,
            (
                f"ci-server-{generation}-{target}-*.log",
                f"ci-server-install-{generation}-{target}.log",
            ),
        )
        print(f"Collected {copied} artifact file(s) for {target} -> {destination.relative_to(ROOT)}")
        if status != 0:
            return status

    # Compatibility matrix, after validate + every build/test/smoke target, matching workflow dependencies.
    props = read_properties(ROOT / "build-config" / "targets.properties")
    compat_env_base = environment_with_java(base_env, jdks[17])
    compat_env_base["BUILD_GENERATION"] = "legacy"
    for target, profile in COMPATIBILITY:
        dependency = props.get(f"target.{target}.deps.{profile}", "")
        name = f"Smoke-test compatibility server [{target}/{profile}]"
        if not dependency:
            record(name, 0, None, skipped=True)
            print(f"==> {name}: skipped (profile dependency not configured)")
            continue
        step_number += 1
        env = dict(compat_env_base)
        env["STONECUTTER_TARGET"] = target
        env["SERVER_STARTUP_TIMEOUT"] = "360"
        env["SERVER_RUNTIME_PROFILE"] = profile
        runtime_log_dir = run_dir / "runtime"
        runtime_log_dir.mkdir(parents=True, exist_ok=True)
        env["SERVER_LOG_FILE"] = str(runtime_log_dir / f"ci-server-legacy-{target}-{profile}.log")
        if os.name == "nt":
            status, log = run_native_server_smoke(
                name, target=target, generation="legacy", profile=profile, java_home=jdks[17],
                env=env, run_dir=run_dir, step_number=step_number,
            )
        else:
            status, log = run_command(
                name, ("bash", "scripts/ci-server-smoke.sh"), env=env,
                run_dir=run_dir, step_number=step_number,
            )
        record(name, status, log)
        destination = run_dir / "artifacts" / f"buildcraft-compat-{target}-{profile}"
        copied = copy_artifact_patterns(compatibility_artifact_patterns(target, profile), destination)
        copied += copy_runtime_logs(
            run_dir, destination, (f"ci-server-legacy-{target}-{profile}.log",)
        )
        print(f"Collected {copied} compatibility artifact file(s) -> {destination.relative_to(ROOT)}")
        if status != 0:
            return status

    summary["finished"] = dt.datetime.now().astimezone().isoformat()
    summary["result"] = "success"
    (run_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(f"\nFull local CI passed. Logs and artifacts: {run_dir.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except LocalCIError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2)
