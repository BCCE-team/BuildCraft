#!/usr/bin/env python3
"""Report the current BCCE source architecture reproducibly.

The reporter is read-only with respect to maintained source trees. It materializes
each configured target into a temporary directory, hashes the result, and reports
source ownership, duplication and architecture drift without changing gameplay.

It does *not* modify buildcraft.api. API files are hashed as an architecture
consistency guard.
"""
from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import asdict, dataclass
from difflib import SequenceMatcher
from hashlib import sha256
import json
from pathlib import Path
import re
import statistics
import subprocess
import tempfile
import time
from typing import Iterable

from source_layout import ROOT, effective_source_files, load_properties, materialize_target, target_ids, target_layout

CONDITIONAL_RE = re.compile(r"^[ \t]*(?://\?|/\*\?)[ \t]*if\b([^\r\n]*)", re.MULTILINE)
LOADER_IMPORT_RE = re.compile(r"^\s*import\s+(net\.(?:minecraftforge|neoforged|fabricmc)\.[^;]+);", re.MULTILINE)
PACKAGE_RE = re.compile(r"^\s*package\s+([^;]+);", re.MULTILINE)
JAVA_PATH_LITERAL_RE = re.compile(r"[\"']([^\"']+\.java)[\"']")

BASELINE_VALIDATIONS: tuple[tuple[str, ...], ...] = (
    ("python", "scripts/validate-stonecutter.py"),
    ("python", "scripts/validate-source-families.py"),
    ("python", "scripts/validate-architecture-hardening.py"),
    ("python", "scripts/validate-repository-cleanliness.py"),
    ("python", "scripts/validate-1.20.1-target.py", "--source-root", "version-src/1.20.1-forge"),
    ("python", "scripts/validate-behavior-parity.py"),
    ("python", "scripts/validate-12111-parity.py"),
    ("python", "scripts/validate-fe-compat.py"),
    ("python", "scripts/validate-fe-mj-engine-parity.py"),
    ("python", "scripts/validate-regressions.py"),
    ("python", "scripts/validate-guide-runtime-claims.py"),
    ("python", "scripts/validate-cross-target-integrity.py"),
)


@dataclass(frozen=True)
class FileStats:
    files: int
    java: int
    resources: int
    bytes: int


@dataclass(frozen=True)
class OverrideStats:
    files: int = 0
    java: int = 0
    additions: int = 0
    overrides: int = 0
    identical_overrides: int = 0
    similar_java_overrides_ge_90: int = 0
    java_override_similarity_median: float | None = None


def digest_bytes(data: bytes) -> str:
    return sha256(data).hexdigest()


def digest_file(path: Path) -> str:
    h = sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def file_map(root: Path) -> dict[str, Path]:
    if not root.exists():
        return {}
    return {
        path.relative_to(root).as_posix(): path
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def source_file_map(root: Path) -> dict[str, Path]:
    """Map maintained layer paths using the same logical paths as source_layout."""
    return file_map(root)


def stats_for_paths(paths: Iterable[Path], root: Path | None = None) -> FileStats:
    files = java = resources = size = 0
    for path in paths:
        if not path.is_file():
            continue
        files += 1
        size += path.stat().st_size
        if path.suffix == ".java":
            java += 1
        elif root is None or "src/main/resources" in path.as_posix() or path.suffix != ".class":
            resources += 1
    return FileStats(files=files, java=java, resources=resources, bytes=size)


def stats_for_root(root: Path) -> FileStats:
    return stats_for_paths((p for p in root.rglob("*") if p.is_file()), root)


def normalize_text_for_similarity(path: Path) -> tuple[str, ...] | None:
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None
    # Keep semantics and comments; only remove platform-dependent newline and
    # trailing-space noise so the metric is stable across checkouts.
    return tuple(line.rstrip() for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"))


def similarity(left: Path, right: Path) -> float | None:
    a = normalize_text_for_similarity(left)
    b = normalize_text_for_similarity(right)
    if a is None or b is None:
        return None
    if a == b:
        return 1.0
    return SequenceMatcher(None, a, b, autojunk=False).ratio()


def override_stats(layer: dict[str, Path], lower_effective: dict[str, Path]) -> OverrideStats:
    similarities: list[float] = []
    identical = 0
    similar_90 = 0
    overrides = 0
    java_overrides = 0
    additions = 0
    java = sum(1 for path in layer.values() if path.suffix == ".java")

    for rel, path in layer.items():
        lower = lower_effective.get(rel)
        if lower is None:
            additions += 1
            continue
        overrides += 1
        if path.suffix == ".java":
            java_overrides += 1
        if digest_file(path) == digest_file(lower):
            identical += 1
            if path.suffix == ".java":
                similarities.append(1.0)
                similar_90 += 1
            continue
        if path.suffix == ".java" and lower.suffix == ".java":
            score = similarity(path, lower)
            if score is not None:
                similarities.append(score)
                if score >= 0.90:
                    similar_90 += 1

    return OverrideStats(
        files=len(layer),
        java=java,
        additions=additions,
        overrides=overrides,
        identical_overrides=identical,
        similar_java_overrides_ge_90=similar_90,
        java_override_similarity_median=(round(statistics.median(similarities), 6) if similarities else None),
    )


def conditional_stats(paths: Iterable[Path]) -> dict[str, object]:
    files = 0
    blocks = 0
    by_expression: Counter[str] = Counter()
    max_blocks = 0
    max_files: list[str] = []
    for path in paths:
        if path.suffix != ".java":
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        matches = list(CONDITIONAL_RE.finditer(text))
        if not matches:
            continue
        files += 1
        count = len(matches)
        blocks += count
        if count > max_blocks:
            max_blocks = count
            max_files = [path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else path.as_posix()]
        elif count == max_blocks:
            max_files.append(path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else path.as_posix())
        for match in matches:
            expression = re.sub(r"\s+", " ", match.group(1).strip(" {\t")) or "<empty>"
            by_expression[expression] += 1
    return {
        "files": files,
        "blocks": blocks,
        "max_blocks_in_one_file": max_blocks,
        "max_files": sorted(max_files),
        "by_expression": dict(sorted(by_expression.items())),
    }


def loader_import_stats(layer_maps: dict[str, dict[str, Path]]) -> dict[str, object]:
    by_layer: dict[str, dict[str, object]] = {}
    total_files: set[str] = set()
    import_counts: Counter[str] = Counter()
    for layer_name, mapping in layer_maps.items():
        layer_files: set[str] = set()
        layer_imports: Counter[str] = Counter()
        for rel, path in mapping.items():
            if path.suffix != ".java":
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for match in LOADER_IMPORT_RE.finditer(text):
                qualified = match.group(1)
                if qualified.startswith("net.minecraftforge"):
                    loader = "forge"
                elif qualified.startswith("net.neoforged"):
                    loader = "neoforge"
                else:
                    loader = "fabric"
                layer_imports[loader] += 1
                import_counts[loader] += 1
                layer_files.add(rel)
                total_files.add(f"{layer_name}:{rel}")
        by_layer[layer_name] = {
            "files": len(layer_files),
            "imports": sum(layer_imports.values()),
            "by_loader": dict(sorted(layer_imports.items())),
        }
    return {
        "files": len(total_files),
        "imports": sum(import_counts.values()),
        "by_loader": dict(sorted(import_counts.items())),
        "by_layer": by_layer,
    }


def foreign_package_stats(paths: Iterable[Path]) -> dict[str, object]:
    prefixes = ("net.minecraft.", "net.neoforged.", "net.minecraftforge.", "net.fabricmc.", "com.mojang.")
    files: list[str] = []
    counts: Counter[str] = Counter()
    for path in paths:
        if path.suffix != ".java":
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        match = PACKAGE_RE.search(text)
        if not match:
            continue
        package = match.group(1).strip()
        for prefix in prefixes:
            if package.startswith(prefix):
                rel = path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else path.as_posix()
                files.append(rel)
                counts[prefix[:-1]] += 1
                break
    return {"files": len(files), "by_prefix": dict(sorted(counts.items())), "paths": sorted(files)}


def tree_manifest(root: Path, *, prefix: str | None = None) -> dict[str, str]:
    result: dict[str, str] = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        if prefix and not rel.startswith(prefix):
            continue
        result[rel] = digest_file(path)
    return result


def tree_digest(manifest: dict[str, str]) -> str:
    h = sha256()
    for rel, file_hash in sorted(manifest.items()):
        h.update(rel.encode("utf-8"))
        h.update(b"\0")
        h.update(file_hash.encode("ascii"))
        h.update(b"\n")
    return h.hexdigest()


def sub_manifest(manifest: dict[str, str], prefix: str) -> dict[str, str]:
    return {rel: value for rel, value in manifest.items() if rel.startswith(prefix)}


def materializer_stats() -> dict[str, object]:
    scripts: list[dict[str, object]] = []
    candidates = [
        ROOT / "scripts" / "source_layout.py",
        ROOT / "scripts" / "source_config.py",
        ROOT / "scripts" / "source_preprocessor.py",
        *sorted((ROOT / "scripts" / "transforms").glob("*.py")),
    ]
    seen: set[Path] = set()
    for path in candidates:
        path = path.resolve()
        if path in seen or not path.is_file():
            continue
        seen.add(path)
        text = path.read_text(encoding="utf-8")
        scripts.append({
            "path": path.relative_to(ROOT).as_posix(),
            "lines": len(text.splitlines()),
            "text_replace_calls": text.count(".replace("),
            "re_sub_calls": len(re.findall(r"\bre\.sub\s*\(", text)),
            "relative_endswith_calls": text.count("relative.endswith("),
            "java_path_literals": len(JAVA_PATH_LITERAL_RE.findall(text)),
            "sha256": digest_file(path),
        })
    return {
        "scripts": scripts,
        "totals": {
            "lines": sum(int(item["lines"]) for item in scripts),
            "text_replace_calls": sum(int(item["text_replace_calls"]) for item in scripts),
            "re_sub_calls": sum(int(item["re_sub_calls"]) for item in scripts),
            "relative_endswith_calls": sum(int(item["relative_endswith_calls"]) for item in scripts),
            "java_path_literals": sum(int(item["java_path_literals"]) for item in scripts),
        },
    }


def platform_similarity_report() -> dict[str, object]:
    platform_root = ROOT / "source-platforms"
    platforms = sorted(path.name for path in platform_root.iterdir() if path.is_dir()) if platform_root.exists() else []
    result: dict[str, object] = {}
    for index, left_name in enumerate(platforms):
        for right_name in platforms[index + 1:]:
            left = source_file_map(platform_root / left_name)
            right = source_file_map(platform_root / right_name)
            common_java = sorted(
                rel for rel in set(left) & set(right)
                if left[rel].suffix == ".java" and right[rel].suffix == ".java"
            )
            scores: list[float] = []
            identical = 0
            ge90 = 0
            for rel in common_java:
                if digest_file(left[rel]) == digest_file(right[rel]):
                    score = 1.0
                    identical += 1
                else:
                    score = similarity(left[rel], right[rel])
                if score is None:
                    continue
                scores.append(score)
                if score >= 0.90:
                    ge90 += 1
            result[f"{left_name}_vs_{right_name}"] = {
                "common_java_paths": len(common_java),
                "compared": len(scores),
                "identical": identical,
                "similar_ge_90": ge90,
                "median_similarity": round(statistics.median(scores), 6) if scores else None,
                "mean_similarity": round(statistics.mean(scores), 6) if scores else None,
            }
    return result


def validation_source_manifest() -> dict[str, str]:
    paths: set[Path] = set((ROOT / "scripts").glob("validate-*.py"))
    paths.update((ROOT / "scripts" / "tests").rglob("*.py"))
    paths.update((ROOT / "scripts" / "checks").rglob("*.py"))
    paths.update((ROOT / "scripts").glob("ci-*-smoke.sh"))
    result: dict[str, str] = {}
    for path in sorted(paths):
        if path.is_file():
            result[path.relative_to(ROOT).as_posix()] = digest_file(path)
    return result


def validation_inventory() -> list[dict[str, object]]:
    result: list[dict[str, object]] = []
    for command in BASELINE_VALIDATIONS:
        script = next((part for part in command if part.startswith("scripts/") and part.endswith((".py", ".sh"))), None)
        script_path = ROOT / script if script else None
        result.append({
            "command": list(command),
            "script_sha256": digest_file(script_path) if script_path and script_path.is_file() else None,
        })
    return result


def input_tree_manifest(props: dict[str, str]) -> dict[str, str]:
    roots: set[Path] = {
        ROOT / "source-shared",
        ROOT / "source-families",
        ROOT / "source-platforms",
        ROOT / "source-family-platforms",
        ROOT / "source-downports",
        ROOT / "resource-src",
        ROOT / "version-src",
        ROOT / "addon-fixture",
        ROOT / "build-config",
        ROOT / "build-logic",
    }
    for target in target_ids(props):
        generation = props[f"target.{target}.build.generation"]
        roots.add(ROOT / "builds" / generation / "targets.properties")
    paths: set[Path] = {
        ROOT / "scripts" / "source_layout.py",
        ROOT / "scripts" / "source_config.py",
        ROOT / "scripts" / "source_preprocessor.py",
    }
    paths.update((ROOT / "scripts" / "transforms").glob("*.py"))
    for root in roots:
        if root.is_file():
            paths.add(root)
        elif root.is_dir():
            paths.update(path for path in root.rglob("*") if path.is_file())
    manifest: dict[str, str] = {}
    for path in sorted(paths):
        if not path.is_file():
            continue
        try:
            rel = path.relative_to(ROOT).as_posix()
        except ValueError:
            continue
        manifest[rel] = digest_file(path)
    return manifest


def layer_report(layout) -> tuple[dict[str, object], dict[str, dict[str, Path]]]:
    named_roots = [("shared", layout.shared_root), ("family", layout.family_root)]
    if layout.family_downport_root is not None:
        named_roots.append(("family_downport", layout.family_downport_root))
    named_roots.append(("platform", layout.platform_root))
    named_roots.append(("family_platform", layout.family_platform_root))
    if layout.family_platform_downport_root is not None:
        named_roots.append(("family_platform_downport", layout.family_platform_downport_root))
    named_roots.append(("target", layout.overlay_root))

    lower: dict[str, Path] = {}
    reports: dict[str, object] = {}
    maps: dict[str, dict[str, Path]] = {}
    for name, root in named_roots:
        mapping = source_file_map(root)
        maps[name] = mapping
        stats = override_stats(mapping, lower)
        reports[name] = {
            "root": root.relative_to(ROOT).as_posix() if root.is_relative_to(ROOT) else root.as_posix(),
            "exists": root.exists(),
            **asdict(stats),
        }
        lower.update(mapping)
    return reports, maps


def effective_source_report(materialized: Path) -> tuple[dict[str, object], dict[str, str]]:
    manifest = tree_manifest(materialized, prefix="src/")
    java_manifest = {rel: value for rel, value in manifest.items() if rel.startswith("src/main/java/") and rel.endswith(".java")}
    resource_manifest = {rel: value for rel, value in manifest.items() if rel.startswith("src/main/resources/")}
    api_manifest = {rel: value for rel, value in java_manifest.items() if "/buildcraft/api/" in rel}
    return ({
        "files": len(manifest),
        "java": len(java_manifest),
        "resources": len(resource_manifest),
        "tree_sha256": tree_digest(manifest),
        "java_tree_sha256": tree_digest(java_manifest),
        "resource_tree_sha256": tree_digest(resource_manifest),
        "api_files": len(api_manifest),
        "api_tree_sha256": tree_digest(api_manifest),
    }, manifest)


def target_report(target: str, props: dict[str, str], temp_root: Path) -> tuple[dict[str, object], dict[str, str]]:
    layout = target_layout(target, props)
    layer_stats, layer_maps = layer_report(layout)

    # Loader imports outside loader-owned platform/family-platform layers are architecture-drift indicators.
    # Target overlays are included because they are part of maintained target ownership.
    outside_platform_maps = {
        name: mapping for name, mapping in layer_maps.items()
        if name not in {"platform", "family_platform", "family_platform_downport"}
    }

    effective_physical = effective_source_files(layout, props)
    conditionals = conditional_stats(effective_physical.values())
    foreign_packages = foreign_package_stats(effective_physical.values())

    destination = temp_root / target
    materialized = materialize_target(target, destination, props)
    effective, manifest = effective_source_report(materialized)

    overlay_map = layer_maps["target"]
    overlay_java = sum(1 for p in overlay_map.values() if p.suffix == ".java")
    overlay_resources = sum(1 for rel in overlay_map if rel.startswith("src/main/resources/"))

    return ({
        "generation": layout.generation,
        "family": layout.family,
        "platform": layout.platform,
        "minecraft": props.get(f"target.{target}.deps.minecraft", ""),
        "layers": layer_stats,
        "overlay_budget": {
            "java": overlay_java,
            "resources": overlay_resources,
            "status": "excellent" if overlay_java <= 10 else "normal" if overlay_java <= 25 else "warning" if overlay_java <= 50 else "architectural_debt",
        },
        "conditionals": conditionals,
        "loader_imports_outside_platform": loader_import_stats(outside_platform_maps),
        "foreign_package_declarations": foreign_packages,
        "effective": effective,
    }, manifest)


def markdown_report(report: dict[str, object]) -> str:
    lines: list[str] = []
    lines.append("# BCCE Source Architecture Report")
    lines.append("")
    lines.append(f"Snapshot label: `{report['baseline_label']}`")
    lines.append("")
    lines.append("> Architecture snapshot. `buildcraft.api` remains frozen and is only hashed as a change guard.")
    lines.append("")
    lines.append("## Scope")
    lines.append("")
    lines.append("- `buildcraft.api` is frozen for this architecture migration. It is hashed only as a guard against accidental changes.")
    lines.append("- Effective-source hashes are produced by the current materializer, including all current 1.21.11 compatibility rewrites.")
    lines.append("- Materialization precedence is `shared < family < platform < family-platform < target`.")
    lines.append("")
    lines.append("## Repository snapshot")
    lines.append("")
    lines.append(f"- Materialization input tree SHA-256: `{report['repository']['input_tree_sha256']}`")
    materializer = report["repository"]["materializer"]
    lines.append(f"- Materializer/helper Python lines: **{materializer['totals']['lines']}**")
    lines.append(f"- `.replace(...)` calls: **{materializer['totals']['text_replace_calls']}**")
    lines.append(f"- `re.sub(...)` calls: **{materializer['totals']['re_sub_calls']}**")
    lines.append(f"- Java-path literals in materializer helpers: **{materializer['totals']['java_path_literals']}**")
    lines.append(f"- Frozen architecture/gameplay/parity validator sources: **{report['repository']['validation_sources_files']} files**, SHA-256 `{report['repository']['validation_sources_sha256']}`")
    lines.append(f"- Non-API validation commands inventoried: **{len(report['repository']['validation_inventory'])}**")
    versioned = report["repository"].get("versioned_resource_sources", {})
    lines.append(f"- Versioned resource-source files: **{versioned.get('files', 0)}**")
    lines.append("")

    platform_similarity = report["repository"].get("platform_similarity", {})
    if platform_similarity:
        lines.append("## Platform duplication snapshot")
        lines.append("")
        lines.append("| Pair | Common Java paths | Identical | >=90% similar | Median similarity |")
        lines.append("|---|---:|---:|---:|---:|")
        for name, values in sorted(platform_similarity.items()):
            median = values["median_similarity"]
            median_text = f"{median * 100:.1f}%" if median is not None else "-"
            lines.append(f"| `{name}` | {values['common_java_paths']} | {values['identical']} | {values['similar_ge_90']} | {median_text} |")
        lines.append("")

    lines.append("## Target snapshot")
    lines.append("")
    lines.append("| Target | Effective Java | Resources | Overlay Java | Overlay resources | Conditions | Loader-import files outside platform | Effective tree SHA | API SHA |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---|---|")
    for target, values in report["targets"].items():
        eff = values["effective"]
        overlay = values["overlay_budget"]
        cond = values["conditionals"]
        loader = values["loader_imports_outside_platform"]
        lines.append(
            f"| `{target}` | {eff['java']} | {eff['resources']} | {overlay['java']} | {overlay['resources']} | "
            f"{cond['blocks']} in {cond['files']} files | {loader['files']} | `{eff['tree_sha256'][:12]}` | `{eff['api_tree_sha256'][:12]}` |"
        )
    lines.append("")

    lines.append("## Layer ownership snapshot")
    lines.append("")
    for target, values in report["targets"].items():
        lines.append(f"### {target}")
        lines.append("")
        lines.append("| Layer | Files | Java | Additions | Overrides | Identical overrides | Java overrides >=90% |")
        lines.append("|---|---:|---:|---:|---:|---:|---:|")
        for layer_name in ("shared", "family", "platform", "family_platform", "target"):
            layer = values["layers"][layer_name]
            lines.append(
                f"| `{layer_name}` | {layer['files']} | {layer['java']} | {layer['additions']} | {layer['overrides']} | "
                f"{layer['identical_overrides']} | {layer['similar_java_overrides_ge_90']} |"
            )
        lines.append("")

    lines.append("## Regeneration")
    lines.append("")
    lines.append("```bash")
    lines.append("python scripts/report-source-architecture.py \\")
    lines.append(f"  --label {report['baseline_label']} \\")
    lines.append("  --json build/reports/source-architecture/report.json \\")
    lines.append("  --hashes build/reports/source-architecture/effective-source-hashes.json \\")
    lines.append("  --markdown build/reports/source-architecture/report.md")
    lines.append("```")
    lines.append("")
    lines.append("The report is generated under build/reports and is not an authoritative source file.")
    lines.append("")
    return "\n".join(lines)


def build_report(label: str) -> tuple[dict[str, object], dict[str, object]]:
    props = load_properties()
    inputs = input_tree_manifest(props)
    validation_sources = validation_source_manifest()
    report: dict[str, object] = {
        "schema_version": 1,
        "baseline_label": label,
        "api_policy": "frozen; not part of the architecture migration; hashed only as a change guard",
        "configured_targets": target_ids(props),
        "repository": {
            "input_tree_files": len(inputs),
            "input_tree_sha256": tree_digest(inputs),
            "materializer": materializer_stats(),
            "platform_similarity": platform_similarity_report(),
            "generator_sources": asdict(stats_for_root(ROOT / "generators")) if (ROOT / "generators").exists() else asdict(FileStats(0, 0, 0, 0)),
            "versioned_resource_sources": asdict(stats_for_root(ROOT / "resource-src")) if (ROOT / "resource-src").exists() else asdict(FileStats(0, 0, 0, 0)),
            "validation_sources_files": len(validation_sources),
            "validation_sources_sha256": tree_digest(validation_sources),
            "validation_sources": validation_sources,
            "validation_inventory": validation_inventory(),
        },
        "targets": {},
    }
    manifests: dict[str, object] = {
        "schema_version": 1,
        "baseline_label": label,
        "targets": {},
    }

    with tempfile.TemporaryDirectory(prefix="bcce-source-architecture-") as temp:
        temp_root = Path(temp)
        for target in target_ids(props):
            values, manifest = target_report(target, props, temp_root)
            report["targets"][target] = values
            manifests["targets"][target] = {
                "tree_sha256": values["effective"]["tree_sha256"],
                "files": manifest,
            }

    return report, manifests


def run_baseline_validations() -> dict[str, object]:
    results: list[dict[str, object]] = []
    for command in BASELINE_VALIDATIONS:
        started = time.monotonic()
        try:
            completed = subprocess.run(
                command,
                cwd=ROOT,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=180,
                check=False,
            )
            exit_code = completed.returncode
            output = completed.stdout
        except subprocess.TimeoutExpired as exc:
            exit_code = 124
            output = exc.stdout or ""
            if isinstance(output, bytes):
                output = output.decode("utf-8", errors="replace")
            output += "\nTIMEOUT after 180 seconds"
        elapsed = round(time.monotonic() - started, 3)
        output_lines = output.splitlines()
        results.append({
            "command": list(command),
            "exit_code": exit_code,
            "elapsed_seconds": elapsed,
            "output_tail": output_lines[-20:],
        })
    return {
        "scope": "architecture/gameplay/parity validators only; public API validators intentionally excluded",
        "commands": len(results),
        "passed": sum(1 for result in results if result["exit_code"] == 0),
        "failed": sum(1 for result in results if result["exit_code"] != 0),
        "results": results,
    }


def validation_markdown(value: dict[str, object]) -> str:
    lines = [
        "# BCCE Validation Snapshot",
        "",
        "> Public API validators are intentionally outside this architecture snapshot. Existing CI still owns them.",
        "",
        f"Commands: **{value['commands']}**; passed: **{value['passed']}**; failed: **{value['failed']}**.",
        "",
        "| Command | Result | Time |",
        "|---|---|---:|",
    ]
    for result in value["results"]:
        command = " ".join(result["command"])
        state = "PASS" if result["exit_code"] == 0 else f"FAIL ({result['exit_code']})"
        lines.append(f"| `{command}` | {state} | {result['elapsed_seconds']:.3f}s |")
    lines.extend([
        "",
        "Gradle builds, GameTests and client/server smoke tests are not executed by this local architecture report. They remain CI/runtime acceptance gates.",
        "",
    ])
    return "\n".join(lines)


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8", newline="")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", default="current")
    parser.add_argument("--json", type=Path, help="write the machine-readable summary")
    parser.add_argument("--hashes", type=Path, help="write per-file effective-source hashes")
    parser.add_argument("--markdown", type=Path, help="write a human-readable summary")
    parser.add_argument("--run-validations", action="store_true", help="run the current non-API architecture/gameplay/parity validator set")
    parser.add_argument("--validation-json", type=Path, help="write validation results as JSON")
    parser.add_argument("--validation-markdown", type=Path, help="write validation results as Markdown")
    parser.add_argument("--stdout", choices=("json", "markdown", "none"), default="markdown")
    args = parser.parse_args()

    report, manifests = build_report(args.label)

    if args.json:
        write_json(args.json, report)
    if args.hashes:
        write_json(args.hashes, manifests)
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(markdown_report(report), encoding="utf-8", newline="")

    if args.run_validations:
        validations = run_baseline_validations()
        if args.validation_json:
            write_json(args.validation_json, validations)
        if args.validation_markdown:
            args.validation_markdown.parent.mkdir(parents=True, exist_ok=True)
            args.validation_markdown.write_text(validation_markdown(validations), encoding="utf-8", newline="")
        if validations["failed"]:
            print(f"Architecture validation snapshot has {validations['failed']} failing command(s).")
            return 1

    if args.stdout == "json":
        print(json.dumps(report, indent=2, sort_keys=True, ensure_ascii=False))
    elif args.stdout == "markdown":
        print(markdown_report(report))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
