#!/usr/bin/env python3
"""Reject stale compatibility debris and generated junk from maintained BCCE sources."""

from __future__ import annotations

from pathlib import Path
import subprocess
import sys

from source_layout import effective_source_files, load_properties, target_ids, target_layout

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOTS = [
    ROOT / "source-shared",
    ROOT / "source-families",
    ROOT / "source-platforms",
    ROOT / "source-family-platforms",
    ROOT / "version-src",
]

errors: list[str] = []


def tracked_repository_files() -> list[Path] | None:
    """Return Git-tracked files, or None when Git metadata is unavailable.

    Generated caches created by validation/build tools are workspace state, not
    repository debris. Only fail for generated/backup files that were actually
    added to Git. Source-tree checks below still work from source ZIPs without
    a .git directory.
    """
    try:
        result = subprocess.run(
            ["git", "ls-files", "-z"],
            cwd=ROOT,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None

    files: list[Path] = []
    for raw_path in result.stdout.split(b"\0"):
        if not raw_path:
            continue
        files.append(ROOT / raw_path.decode(sys.getfilesystemencoding(), errors="surrogateescape"))
    return files


# Files that are never valid tracked repository sources/artifacts. Do not scan
# arbitrary workspace files here: Python/Gradle validation steps legitimately
# create ignored caches and build outputs during CI.
tracked_files = tracked_repository_files()
if tracked_files is not None:
    for path in tracked_files:
        rel = path.relative_to(ROOT)
        if "__pycache__" in rel.parts or path.suffix == ".pyc":
            errors.append(f"generated Python cache file is tracked: {rel}")
        elif (
            path.name in {".DS_Store", "Thumbs.db"}
            or path.suffix in {".class", ".orig", ".rej", ".swp", ".tmp", ".bak"}
            or path.name.endswith("~")
        ):
            errors.append(f"generated/backup file is tracked: {rel}")

# Scratch-class '$' prefixes are forbidden in maintained sources.
for source_root in SOURCE_ROOTS:
    if not source_root.exists():
        continue
    for path in source_root.rglob("*.java"):
        if "$" in path.name:
            errors.append(f"temporary '$' Java source remains: {path.relative_to(ROOT)}")
        if path.name == "BCMenuBase_Neptune.java":
            errors.append(f"superseded menu base remains: {path.relative_to(ROOT)}")
        if path.name == "ItemGoggles.java":
            errors.append(f"unregistered robot-goggles implementation remains: {path.relative_to(ROOT)}")


# Robot goggles were never registered in any supported target. Keep the dead
# class/assets/atlas references from re-entering maintained sources.
for source_root in SOURCE_ROOTS:
    if not source_root.exists():
        continue
    for path in source_root.rglob("*"):
        if not path.is_file():
            continue
        if path.name in {"goggles.json", "goggles.png"} and "buildcraftcore" in path.parts:
            errors.append(f"unregistered robot-goggles asset remains: {path.relative_to(ROOT)}")
        if path.suffix in {".json", ".java"}:
            text = path.read_text(encoding="utf-8", errors="replace")
            if "buildcraftcore:items/goggles" in text or "item.buildcraft.robot_goggles" in text:
                errors.append(f"unregistered robot-goggles reference remains: {path.relative_to(ROOT)}")

# Exact legacy lifecycle fragments that have no meaning on the supported ports.
for source_root in SOURCE_ROOTS:
    if not source_root.exists():
        continue
    for path in source_root.rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="replace")
        rel = path.relative_to(ROOT)
        for marker in (
            "@Mod.EventHandler",
            "NetworkRegistry.INSTANCE.registerGuiHandler",
            "FMLPreInitializationEvent",
            "FMLPostInitializationEvent",
            "player.openGui(",
        ):
            if marker in text:
                errors.append(f"obsolete Forge lifecycle marker {marker!r} remains in {rel}")
        if "TEMPORARY CLASS DO NOT USE" in text:
            errors.append(f"temporary implementation marker remains in {rel}")


# Resource layout is generation-specific after 1.21. Validate the effective
# source tree of every configured target so platform layers and configured targets
# are covered without hard-coded directory lists.
def check_resource_generation(target: str, family: str) -> None:
    legacy_only = {"advancements", "loot_tables", "recipes", "structures"}
    modern_only = {"advancement", "loot_table", "recipe", "structure"}
    legacy_tag_kinds = {"blocks", "items", "fluids"}
    modern_tag_kinds = {"block", "item", "fluid"}

    layout = target_layout(target, properties)
    for relative, source_path in effective_source_files(layout, properties).items():
        parts = Path(relative).parts
        # Expected prefix: src/<source-set>/resources/data/<namespace>/...
        if len(parts) < 7 or parts[0] != "src" or parts[2] != "resources" or parts[3] != "data":
            continue
        data_parts = parts[5:]
        if not data_parts:
            continue
        top = data_parts[0]
        source_label = source_path.relative_to(ROOT)
        if family == "legacy" and top in modern_only:
            errors.append(
                f"{target}: modern resource directory {top!r} remains in legacy source: {source_label}"
            )
        if family == "modern" and top in legacy_only:
            errors.append(
                f"{target}: legacy resource directory {top!r} remains in modern source: {source_label}"
            )
        if len(data_parts) >= 2 and top == "tags":
            kind = data_parts[1]
            if family == "legacy" and kind in modern_tag_kinds:
                errors.append(
                    f"{target}: modern tag directory {kind!r} remains in legacy source: {source_label}"
                )
            if family == "modern" and kind in legacy_tag_kinds:
                errors.append(
                    f"{target}: legacy tag directory {kind!r} remains in modern source: {source_label}"
                )


properties = load_properties()
for configured_target in target_ids(properties):
    configured_layout = target_layout(configured_target, properties)
    check_resource_generation(configured_target, configured_layout.family)

# Mining tool tags belong to the minecraft namespace. Module-local copies from
# the early ports were ignored by vanilla and drifted independently.
for source_root in SOURCE_ROOTS:
    if not source_root.exists():
        continue
    for path in source_root.rglob("*.json"):
        rel_parts = path.relative_to(source_root).parts
        if "data" not in rel_parts:
            continue
        try:
            i = rel_parts.index("data")
        except ValueError:
            continue
        if len(rel_parts) <= i + 4:
            continue
        namespace = rel_parts[i + 1]
        if (
            namespace.startswith("buildcraft")
            and rel_parts[i + 2] == "tags"
            and rel_parts[i + 3] in {"block", "blocks"}
            and rel_parts[i + 4] == "mineable"
        ):
            errors.append(f"obsolete module-local mining tag remains: {path.relative_to(ROOT)}")
        if namespace == "buildcraftbuilder":
            errors.append(f"obsolete typo namespace 'buildcraftbuilder' remains: {path.relative_to(ROOT)}")

# These obsolete strings describe unsupported content and must not re-enter current language data.
lang = ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json"
if lang.exists():
    text = lang.read_text(encoding="utf-8", errors="replace")
    for stale in (
        "Robots aren't coming to 1.12.2",
        "Not usable in survival",
        "Nothing can be charged with this!",
        "No recipes use this!",
    ):
        if stale in text:
            errors.append(f"obsolete tooltip remains in {lang.relative_to(ROOT)}: {stale!r}")

if errors:
    print("Repository cleanliness validation failed:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Repository cleanliness OK: no tracked generated junk, known scratch classes, obsolete lifecycle/UI shims, wrong-generation resource paths, stale module-local mining tags, or stale 1.12 tooltips detected")
