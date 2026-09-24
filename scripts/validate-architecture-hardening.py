#!/usr/bin/env python3
"""Enforce architecture budgets and emit a trend report.

The guard measures maintained source ownership rather than materialized build
output. It prevents architecture drift into target overlays, conditional jungles,
loader-owned gameplay copies, class-specific Python rewrites, or stale downports.
"""
from __future__ import annotations

import argparse
from collections import Counter
from difflib import SequenceMatcher
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Iterable

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from source_layout import ROOT, effective_source_files, load_properties, target_ids, target_layout
from source_preprocessor import version_tuple

BUDGET_FILE = ROOT / "build-config" / "architecture-budgets.json"
ALLOWLIST_FILE = ROOT / "build-config" / "platform-gameplay-allowlist.txt"

CONDITIONAL_IF_RE = re.compile(r"^[ \t]*(?://\?|/\*\?)[ \t]*if\b", re.MULTILINE)
IF_LINE_RE = re.compile(r"^\s*(?://\?|/\*\?)\s*if\b.*\{\s*(?:\*/)?\s*$")
ELSE_IF_LINE_RE = re.compile(r"^\s*(?://\?|/\*\?)\s*}\s*else\s+if\b.*\{\s*(?:\*/)?\s*$")
ELSE_LINE_RE = re.compile(r"^\s*(?://\?|/\*\?)\s*}\s*else\s*\{\s*(?:\*/)?\s*$")
END_LINE_RE = re.compile(r"^\s*(?://\?|/\*\?)\s*}\s*(?:\*/)?\s*$")
PACKAGE_RE = re.compile(r"^\s*package\s+([^;]+);", re.MULTILINE)
FORBIDDEN_PACKAGE_PREFIXES = (
    "net.minecraft.",
    "net.minecraftforge.",
    "net.neoforged.",
    "net.fabricmc.",
    "com.mojang.",
)
GAMEPLAY_NAME_RE = re.compile(r"^(?:Tile|Pipe|Robot|Gate|Builder|Engine).*\.java$")
JAVA_LITERAL_RE = re.compile(r"[\"']([^\"']+\.java)[\"']")
BUILDCRAFT_JAVA_PATH_RE = re.compile(r"buildcraft/[A-Za-z0-9_./$-]+\.java")


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def normalized_lines(path: Path) -> tuple[str, ...] | None:
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return None
    return tuple(line.rstrip() for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"))


def similarity(left: Path, right: Path) -> float | None:
    a = normalized_lines(left)
    b = normalized_lines(right)
    if a is None or b is None:
        return None
    if a == b:
        return 1.0
    return SequenceMatcher(None, a, b, autojunk=False).ratio()


def file_map(root: Path) -> dict[str, Path]:
    if not root.exists():
        return {}
    return {
        path.relative_to(root).as_posix(): path
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def java_files(root: Path) -> list[Path]:
    return sorted(root.rglob("*.java")) if root.exists() else []


def maintained_java_roots() -> list[Path]:
    roots = [
        ROOT / "source-shared",
        ROOT / "source-families",
        ROOT / "source-platforms",
        ROOT / "source-family-platforms",
        ROOT / "source-downports",
        ROOT / "version-src",
    ]
    return [root for root in roots if root.exists()]


def conditional_metrics() -> dict[str, object]:
    files = 0
    blocks = 0
    max_blocks = 0
    max_block_files: list[str] = []
    long_branches: list[dict[str, object]] = []

    source_roots = [
        ROOT / "source-shared",
        ROOT / "source-families",
        ROOT / "source-platforms",
        ROOT / "source-family-platforms",
    ]
    for root in source_roots:
        if not root.exists():
            continue
        for path in java_files(root):
            text = path.read_text(encoding="utf-8", errors="replace")
            count = len(CONDITIONAL_IF_RE.findall(text))
            if count:
                files += 1
                blocks += count
                rel = path.relative_to(ROOT).as_posix()
                if count > max_blocks:
                    max_blocks = count
                    max_block_files = [rel]
                elif count == max_blocks:
                    max_block_files.append(rel)

            # Branch-span diagnostics. A parent span intentionally includes nested
            # directives: large alternate implementations should become a facade or
            # complete source variant instead of hiding behind inline conditions.
            stack: list[dict[str, int]] = []
            for line_no, line in enumerate(text.splitlines(), 1):
                if IF_LINE_RE.match(line):
                    stack.append({"start": line_no + 1, "directive": line_no})
                    continue
                if ELSE_IF_LINE_RE.match(line) or ELSE_LINE_RE.match(line):
                    if stack:
                        frame = stack[-1]
                        length = max(0, line_no - frame["start"])
                        if length > 20:
                            long_branches.append({"path": path.relative_to(ROOT).as_posix(), "line": frame["directive"], "lines": length})
                        frame["start"] = line_no + 1
                        frame["directive"] = line_no
                    continue
                if END_LINE_RE.match(line):
                    if stack:
                        frame = stack.pop()
                        length = max(0, line_no - frame["start"])
                        if length > 20:
                            long_branches.append({"path": path.relative_to(ROOT).as_posix(), "line": frame["directive"], "lines": length})
                    continue

    return {
        "files": files,
        "blocks": blocks,
        "max_blocks_per_file": max_blocks,
        "max_block_files": sorted(max_block_files),
        "branches_over_20_lines": sorted(long_branches, key=lambda item: (-int(item["lines"]), str(item["path"]), int(item["line"]))),
    }


def target_overlay_metrics(props: dict[str, str]) -> dict[str, dict[str, int]]:
    result: dict[str, dict[str, int]] = {}
    for target in target_ids(props):
        layout = target_layout(target, props)
        mapping = file_map(layout.overlay_root)
        java = sum(1 for path in mapping.values() if path.suffix == ".java")
        resources = sum(1 for rel in mapping if rel.startswith("src/main/resources/"))
        api_java = sum(1 for rel, path in mapping.items() if path.suffix == ".java" and rel.startswith("src/main/java/buildcraft/api/"))
        result[target] = {
            "java": java,
            "resources": resources,
            "api_java": api_java,
            "gameplay_lib_java": java - api_java,
        }
    return result


def platform_roots() -> list[tuple[str, str, Path]]:
    result: list[tuple[str, str, Path]] = []
    root = ROOT / "source-platforms"
    if root.exists():
        for platform_root in sorted(path for path in root.iterdir() if path.is_dir()):
            result.append((platform_root.name, f"source-platforms/{platform_root.name}", platform_root))
    family_root = ROOT / "source-family-platforms"
    if family_root.exists():
        for family in sorted(path for path in family_root.iterdir() if path.is_dir()):
            for platform_root in sorted(path for path in family.iterdir() if path.is_dir()):
                result.append((platform_root.name, f"source-family-platforms/{family.name}/{platform_root.name}", platform_root))
    return result


def platform_metrics() -> dict[str, object]:
    own_token = {
        "forge": "net.minecraftforge",
        "neoforge": "net.neoforged",
        "fabric": "net.fabricmc",
    }
    by_root: dict[str, int] = {}
    neutral: list[str] = []
    gameplay: list[str] = []
    for platform, label, root in platform_roots():
        paths = java_files(root)
        by_root[label] = len(paths)
        token = own_token.get(platform)
        for path in paths:
            text = path.read_text(encoding="utf-8", errors="replace")
            if token and token not in text:
                neutral.append(path.relative_to(ROOT).as_posix())
            if "/src/main/java/" in path.as_posix() and GAMEPLAY_NAME_RE.match(path.name):
                gameplay.append(path.relative_to(ROOT).as_posix())
    return {
        "by_root": dict(sorted(by_root.items())),
        "java_total": sum(by_root.values()),
        "loader_neutral_java": sorted(neutral),
        "gameplay_override_candidates": sorted(gameplay),
    }


def read_allowlist() -> set[str]:
    if not ALLOWLIST_FILE.is_file():
        fail(f"missing {ALLOWLIST_FILE.relative_to(ROOT)}")
    values: set[str] = set()
    for raw in ALLOWLIST_FILE.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        values.add(line)
    return values


def downport_metrics(props: dict[str, str]) -> dict[str, object]:
    canonical_effective_by_family: dict[str, dict[str, Path]] = {}
    for family in {target_layout(target, props).family for target in target_ids(props)}:
        canonical_minecraft = props.get(f"source.family.{family}.canonical_minecraft", "").strip()
        canonical_target = next(
            (
                target for target in target_ids(props)
                if target_layout(target, props).family == family
                and props.get(f"target.{target}.deps.minecraft", "").strip() == canonical_minecraft
            ),
            None,
        )
        if canonical_target is not None:
            canonical_effective_by_family[family] = effective_source_files(target_layout(canonical_target, props), props)

    roots: list[tuple[str, str, Path]] = []
    seen: set[Path] = set()
    for target in target_ids(props):
        layout = target_layout(target, props)
        for root in (layout.family_downport_root, layout.family_platform_downport_root):
            if root is not None and root not in seen:
                seen.add(root)
                roots.append((layout.family, root.relative_to(ROOT).as_posix(), root))

    by_root: dict[str, int] = {}
    identical: list[str] = []
    similar_ge_90: list[dict[str, object]] = []
    total = 0
    for family, label, root in sorted(roots):
        paths = java_files(root)
        by_root[label] = len(paths)
        total += len(paths)
        canonical_effective = canonical_effective_by_family.get(family, {})
        for path in paths:
            rel = path.relative_to(root).as_posix()
            canonical = canonical_effective.get(rel)
            if canonical is None or canonical.suffix != ".java":
                continue
            if digest(path) == digest(canonical):
                identical.append(f"{path.relative_to(ROOT).as_posix()} == {canonical.relative_to(ROOT).as_posix()}")
                continue
            score = similarity(path, canonical)
            if score is not None and score >= 0.90:
                similar_ge_90.append({
                    "path": path.relative_to(ROOT).as_posix(),
                    "canonical": canonical.relative_to(ROOT).as_posix(),
                    "similarity": round(score, 6),
                })

    # A target overlay must never duplicate its own explicit downport view.
    overlay_duplicates: list[str] = []
    for target in target_ids(props):
        layout = target_layout(target, props)
        overlay = file_map(layout.overlay_root)
        downports: dict[str, Path] = {}
        for root in (layout.family_downport_root, layout.family_platform_downport_root):
            if root is not None:
                downports.update(file_map(root))
        for rel in sorted(set(overlay) & set(downports)):
            if digest(overlay[rel]) == digest(downports[rel]):
                overlay_duplicates.append(f"{target}:{rel}")

    return {
        "by_root": dict(sorted(by_root.items())),
        "java_total": total,
        "identical_to_canonical": sorted(identical),
        "similar_to_canonical_ge_90": sorted(similar_ge_90, key=lambda item: (-float(item["similarity"]), str(item["path"]))),
        "target_overlay_duplicates": overlay_duplicates,
    }


def transform_metrics() -> dict[str, object]:
    violations: list[str] = []
    literal_count = 0
    transform_root = ROOT / "scripts" / "transforms"
    for path in sorted(transform_root.glob("*.py")):
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in BUILDCRAFT_JAVA_PATH_RE.finditer(text):
            violations.append(f"{path.relative_to(ROOT).as_posix()}:{match.group(0)}")
        for literal in JAVA_LITERAL_RE.findall(text):
            if literal in {".java", "*.java"}:
                continue
            # Any named Java file in a transform is a class-specific source rewrite.
            literal_count += 1
            violations.append(f"{path.relative_to(ROOT).as_posix()}:{literal}")
    return {
        "class_specific_java_rewrites": len(set(violations)),
        "java_path_literals": literal_count,
        "violations": sorted(set(violations)),
    }


def foreign_package_metrics() -> dict[str, object]:
    violations: list[str] = []
    by_prefix: Counter[str] = Counter()
    for root in maintained_java_roots():
        for path in java_files(root):
            text = path.read_text(encoding="utf-8", errors="replace")
            match = PACKAGE_RE.search(text)
            if not match:
                continue
            package = match.group(1).strip()
            for prefix in FORBIDDEN_PACKAGE_PREFIXES:
                if package.startswith(prefix):
                    violations.append(path.relative_to(ROOT).as_posix())
                    by_prefix[prefix[:-1]] += 1
                    break
    return {
        "count": len(violations),
        "by_prefix": dict(sorted(by_prefix.items())),
        "violations": sorted(violations),
    }


def canonical_metrics(props: dict[str, str]) -> dict[str, object]:
    families: dict[str, dict[str, object]] = {}
    configured_families = [item.strip() for item in props.get("sourceFamilies", "").split(",") if item.strip()]
    for family in configured_families:
        canonical = props.get(f"source.family.{family}.canonical_minecraft", "").strip()
        targets = [target for target in target_ids(props) if target_layout(target, props).family == family]
        versions = {target: props.get(f"target.{target}.deps.minecraft", "").strip() for target in targets}
        newest = max(versions.values(), key=version_tuple) if versions else ""
        canonical_targets = [target for target, version in versions.items() if version == canonical]
        downport_targets: list[str] = []
        for target in canonical_targets:
            layout = target_layout(target, props)
            if layout.family_downport_root is not None or layout.family_platform_downport_root is not None:
                downport_targets.append(target)
        families[family] = {
            "configured": canonical,
            "newest": newest,
            "targets": canonical_targets,
            "canonical_targets_using_downports": downport_targets,
        }
    return {"families": families}


def current_metrics() -> dict[str, object]:
    props = load_properties()
    conditions = conditional_metrics()
    targets = target_overlay_metrics(props)
    platform = platform_metrics()
    downports = downport_metrics(props)
    transforms = transform_metrics()
    foreign = foreign_package_metrics()
    canonical = canonical_metrics(props)
    allowlist = read_allowlist()
    candidates = set(platform["gameplay_override_candidates"])
    return {
        "schema_version": 1,
        "canonical": canonical,
        "targets": targets,
        "conditions": conditions,
        "downports": downports,
        "platform": {
            **platform,
            "loader_neutral_count": len(platform["loader_neutral_java"]),
            "gameplay_override_count": len(platform["gameplay_override_candidates"]),
            "gameplay_allowlist_entries": len(allowlist),
            "gameplay_unallowlisted": sorted(candidates - allowlist),
            "gameplay_stale_allowlist": sorted(allowlist - candidates),
        },
        "transforms": transforms,
        "foreign_packages": foreign,
    }


def load_budget_file(path: Path = BUDGET_FILE) -> dict[str, object]:
    if not path.is_file():
        fail(f"missing {path.relative_to(ROOT)}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        fail(f"invalid architecture budget JSON: {exc}")
    if value.get("schema_version") != 1:
        fail("unsupported architecture budget schema")
    return value


def load_budget_from_git(ref: str | None) -> dict[str, object] | None:
    if not ref:
        return None
    try:
        completed = subprocess.run(
            ["git", "show", f"{ref}:build-config/architecture-budgets.json"],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=20,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if completed.returncode != 0 or not completed.stdout.strip():
        return None
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError:
        return None


def get_metric(metrics: dict[str, object], path: str) -> int:
    value: object = metrics
    parts = path[1:].split("/") if path.startswith("/") else path.split(".")
    for part in parts:
        if not isinstance(value, dict) or part not in value:
            fail(f"budget references unknown metric {path!r}")
        value = value[part]
    if isinstance(value, bool) or not isinstance(value, int):
        fail(f"budget metric {path!r} is not an integer")
    return value




def ratchet_metric_paths(budget: dict[str, object]) -> set[str]:
    raw = budget.get("ratchet_limits", [])
    if not isinstance(raw, list) or any(not isinstance(item, str) for item in raw):
        fail("architecture budgets: ratchet_limits must be an array of metric paths")
    limits = budget.get("limits", {})
    if not isinstance(limits, dict):
        fail("architecture budgets: limits must be an object")
    unknown = sorted(set(raw) - set(limits))
    if unknown:
        fail(f"architecture ratchet references unknown budget metric {unknown[0]!r}")
    return set(raw)


def ratchet_rows(metrics: dict[str, object], budget: dict[str, object], previous: dict[str, object] | None) -> list[tuple[str, int, int, int | None, int | None]]:
    rows: list[tuple[str, int, int, int | None, int | None]] = []
    limits = budget.get("limits", {})
    previous_limits = previous.get("limits", {}) if isinstance(previous, dict) else {}
    ratchets = ratchet_metric_paths(budget)
    for path, maximum in sorted(limits.items()):
        current = get_metric(metrics, path)
        prior = previous_limits.get(path) if isinstance(previous_limits, dict) else None
        ratchet_target = current if path in ratchets and current < int(maximum) else None
        rows.append((path, current, int(maximum), int(prior) if isinstance(prior, int) else None, ratchet_target))
    return rows


def ratchet_errors(metrics: dict[str, object], budget: dict[str, object], previous: dict[str, object] | None) -> list[str]:
    if not isinstance(previous, dict):
        return []
    limits = budget.get("limits", {})
    previous_limits = previous.get("limits", {})
    if not isinstance(limits, dict) or not isinstance(previous_limits, dict):
        return []

    errors: list[str] = []
    for path in sorted(ratchet_metric_paths(budget)):
        maximum = limits.get(path)
        previous_maximum = previous_limits.get(path)
        if not isinstance(maximum, int) or not isinstance(previous_maximum, int):
            continue
        current = get_metric(metrics, path)
        if maximum > previous_maximum:
            errors.append(f"{path}: ratchet cannot increase budget from {previous_maximum} to {maximum}")
            continue
        if current < previous_maximum and maximum != current:
            errors.append(
                f"{path}: metric improved to {current}; ratchet budget must be tightened from {previous_maximum} to {current} (found {maximum})"
            )
    return errors


def validate(metrics: dict[str, object], budget: dict[str, object], previous: dict[str, object] | None = None) -> list[str]:
    errors: list[str] = []
    limits = budget.get("limits", {})
    if not isinstance(limits, dict):
        fail("architecture budgets: limits must be an object")
    for metric_path, maximum in sorted(limits.items()):
        if not isinstance(maximum, int):
            fail(f"architecture budget {metric_path!r} must be an integer")
        current = get_metric(metrics, metric_path)
        if current > maximum:
            errors.append(f"{metric_path}: {current} exceeds budget {maximum}")

    conditions = metrics["conditions"]
    invariants = budget.get("invariants", {})
    max_blocks = int(invariants.get("max_conditional_blocks_per_java", 4))
    if int(conditions["max_blocks_per_file"]) > max_blocks:
        paths = ", ".join(conditions["max_block_files"][:5])
        errors.append(f"conditional block budget: max is {conditions['max_blocks_per_file']} > {max_blocks}; {paths}")

    targets = metrics["targets"]
    frozen_api = set(invariants.get("frozen_12111_api_files", []))
    t12111 = targets.get("1.21.11-neoforge", {})
    if int(t12111.get("gameplay_lib_java", -1)) != 0:
        errors.append(f"1.21.11 target gameplay/lib overrides must be 0, got {t12111.get('gameplay_lib_java')}")
    if int(t12111.get("api_java", -1)) != len(frozen_api):
        errors.append(f"1.21.11 frozen API override count must be {len(frozen_api)}, got {t12111.get('api_java')}")
    layout = target_layout("1.21.11-neoforge", load_properties())
    actual_target_java = {
        rel for rel, path in file_map(layout.overlay_root).items()
        if path.suffix == ".java"
    }
    if actual_target_java != frozen_api:
        errors.append(
            "1.21.11 target Java must contain only frozen API exceptions; "
            f"actual={sorted(actual_target_java)}, expected={sorted(frozen_api)}"
        )

    platform = metrics["platform"]
    if platform["loader_neutral_java"]:
        errors.append(f"loader-neutral Java leaked into platform ownership: {platform['loader_neutral_java'][0]}")
    if platform["gameplay_unallowlisted"]:
        errors.append(f"platform gameplay override missing explicit allowlist entry: {platform['gameplay_unallowlisted'][0]}")
    if platform["gameplay_stale_allowlist"]:
        errors.append(f"stale platform gameplay allowlist entry: {platform['gameplay_stale_allowlist'][0]}")

    downports = metrics["downports"]
    if downports["identical_to_canonical"]:
        errors.append(f"byte-identical downport must be deleted: {downports['identical_to_canonical'][0]}")
    if downports["target_overlay_duplicates"]:
        errors.append(f"target override duplicates its downport: {downports['target_overlay_duplicates'][0]}")

    transforms = metrics["transforms"]
    if int(transforms["class_specific_java_rewrites"]) != 0:
        errors.append(f"class-specific Java transform detected: {transforms['violations'][0]}")

    foreign = metrics["foreign_packages"]
    if int(foreign["count"]) != 0:
        errors.append(f"foreign package declaration in maintained source: {foreign['violations'][0]}")

    canonical_families = metrics["canonical"]["families"]
    for family in ("legacy", "modern"):
        state = canonical_families.get(family, {})
        expected = invariants.get(f"canonical_{family}_minecraft", "")
        configured = state.get("configured", "")
        newest = state.get("newest", "")
        if configured != expected:
            errors.append(f"{family} canonical Minecraft changed unexpectedly: {configured} != {expected}")
        if configured != newest:
            errors.append(f"{family} canonical source is not newest-first: canonical={configured} newest={newest}")
        if not state.get("targets"):
            errors.append(f"{family} canonical Minecraft {configured!r} has no configured production target")
        if state.get("canonical_targets_using_downports"):
            errors.append(
                f"canonical {family} target must not resolve through an older-version downport: "
                f"{state['canonical_targets_using_downports'][0]}"
            )

    errors.extend(ratchet_errors(metrics, budget, previous))
    return errors


def trend_rows(metrics: dict[str, object], budget: dict[str, object], previous: dict[str, object] | None) -> list[tuple[str, int, int, int | None]]:
    rows: list[tuple[str, int, int, int | None]] = []
    limits = budget.get("limits", {})
    previous_limits = previous.get("limits", {}) if isinstance(previous, dict) else {}
    for path, maximum in sorted(limits.items()):
        current = get_metric(metrics, path)
        prior = previous_limits.get(path) if isinstance(previous_limits, dict) else None
        rows.append((path, current, int(maximum), int(prior) if isinstance(prior, int) else None))
    return rows


def markdown(metrics: dict[str, object], budget: dict[str, object], previous: dict[str, object] | None, errors: list[str]) -> str:
    lines = [
        "# Architecture hardening",
        "",
        f"Status: **{'FAIL' if errors else 'PASS'}**",
        "",
        "## Budget trend",
        "",
        "| Metric | Current | Budget | Previous budget | Delta vs previous | Ratchet target |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for path, current, maximum, prior, ratchet_target in ratchet_rows(metrics, budget, previous):
        prior_text = "-" if prior is None else str(prior)
        delta_text = "-" if prior is None else f"{current - prior:+d}"
        ratchet_text = "-" if ratchet_target is None else str(ratchet_target)
        lines.append(f"| `{path}` | {current} | {maximum} | {prior_text} | {delta_text} | {ratchet_text} |")

    cond = metrics["conditions"]
    lines.extend([
        "",
        "## Condition diagnostics",
        "",
        f"- Conditional files: **{cond['files']}**",
        f"- Conditional blocks: **{cond['blocks']}**",
        f"- Max blocks in one Java file: **{cond['max_blocks_per_file']}**",
        f"- Branches over 20 lines: **{len(cond['branches_over_20_lines'])}** (warning only)",
    ])
    for item in cond["branches_over_20_lines"][:10]:
        lines.append(f"  - `{item['path']}:{item['line']}` - {item['lines']} lines")

    down = metrics["downports"]
    lines.extend([
        "",
        "## Downports",
        "",
        f"- Java downports: **{down['java_total']}**",
        f"- Byte-identical to canonical: **{len(down['identical_to_canonical'])}**",
        f"- >=90% similar to canonical: **{len(down['similar_to_canonical_ge_90'])}** (warning; candidates for cleanup)",
    ])
    for item in down["similar_to_canonical_ge_90"][:10]:
        lines.append(f"  - `{item['path']}` - {float(item['similarity']) * 100:.1f}%")

    plat = metrics["platform"]
    lines.extend([
        "",
        "## Platform ownership",
        "",
        f"- Platform Java: **{plat['java_total']}**",
        f"- Loader-neutral Java in platform layers: **{len(plat['loader_neutral_java'])}**",
        f"- Explicit gameplay override allowlist: **{plat['gameplay_allowlist_entries']}** entries",
        f"- Unallowlisted gameplay overrides: **{len(plat['gameplay_unallowlisted'])}**",
        "",
        "| Platform root | Java |",
        "|---|---:|",
    ])
    for root, count in plat["by_root"].items():
        lines.append(f"| `{root}` | {count} |")

    if errors:
        lines.extend(["", "## Errors", ""])
        for error in errors:
            lines.append(f"- {error}")
    return "\n".join(lines) + "\n"


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8", newline="")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--budget-ref", help="optional git ref whose budget file is shown as the previous trend baseline")
    parser.add_argument("--json", type=Path, help="write current architecture metrics as JSON")
    parser.add_argument("--markdown", type=Path, help="write a human-readable CI summary")
    args = parser.parse_args()

    budget = load_budget_file()
    previous = load_budget_from_git(args.budget_ref)
    metrics = current_metrics()
    errors = validate(metrics, budget, previous)

    report = {
        "schema_version": 1,
        "metrics": metrics,
        "budget": budget,
        "previous_budget": previous,
        "errors": errors,
    }
    if args.json:
        write_json(args.json, report)
    text = markdown(metrics, budget, previous, errors)
    if args.markdown:
        args.markdown.parent.mkdir(parents=True, exist_ok=True)
        args.markdown.write_text(text, encoding="utf-8", newline="")

    print(text, end="")
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
