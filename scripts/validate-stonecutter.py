#!/usr/bin/env python3
"""Validate independent build roots, canonical targets and loader build adapters."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from source_layout import (
    resolve_effective_source,
    ROOT,
    TARGETS_PROPERTIES,
    generation_config_paths,
    generation_targets,
    load_properties,
    read_properties,
    target_build_root,
    target_ids,
    target_layout,
)

ACTIVE_PATTERN = re.compile(r'^\s*stonecutter\s+active\s+"([^"]+)"', re.MULTILINE)
PLACEHOLDER_PATTERN = re.compile(r"\$\{([A-Za-z0-9_.-]+)\}")
RESOURCE_KEY_PATTERN = re.compile(r'^\s*([A-Za-z_][A-Za-z0-9_]*)\s*:', re.MULTILINE)

COMMON_REQUIRED = (
    "mod.group", "mod.id", "mod.name", "mod.version", "mod.archive_name",
    "mod.authors", "mod.license", "deps.junit", "source.shared_root",
)
TARGET_REQUIRED = (
    "source.family", "source.platform", "source.root", "source.platform_root",
    "source.overlay_root", "deps.minecraft", "java.version", "network.protocol",
    "pack.format", "pack.resource_format", "pack.data_format",
)
FORGE_REQUIRED = (
    "deps.forge", "loader.version_range", "forge.version_range",
    "minecraft.version_range", "buildcraft.version_range", "compat.jei.range",
    "compat.jade.range", "compat.ic2.range", "compat.forestry.range",
)
NEOFORGE_REQUIRED = (
    "deps.neoforge", "loader.version_range", "neoforge.version_range",
    "minecraft.version_range", "buildcraft.version_range", "compat.jei.range",
    "compat.jade.range", "compat.ic2.range", "compat.forestry.range",
)


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def value(props: dict[str, str], target: str, key: str, *, allow_empty: bool = False) -> str:
    raw = props.get(f"target.{target}.{key}", props.get(f"common.{key}", "")).strip()
    if not allow_empty and not raw:
        fail(f"{target}: missing property {key!r}")
    return raw


def resource_placeholders(target: str, props: dict[str, str]) -> set[str]:
    loader = target_layout(target, props).platform
    candidates = {
        "forge": ("src/main/resources/META-INF/mods.toml", "src/main/resources/pack.mcmeta"),
        "neoforge": ("src/main/resources/META-INF/neoforge.mods.toml", "src/main/resources/pack.mcmeta"),
        "fabric": ("src/main/resources/fabric.mod.json", "src/main/resources/pack.mcmeta"),
    }
    result: set[str] = set()
    layout = target_layout(target, props)
    for relative in candidates.get(loader, ()):
        path = resolve_effective_source(layout, props, relative)
        if path:
            result.update(PLACEHOLDER_PATTERN.findall(path.read_text(encoding="utf-8")))
    return result


def wrapper_version(build_root: Path) -> str:
    path = build_root / "gradle/wrapper/gradle-wrapper.properties"
    if not path.is_file():
        fail(f"missing wrapper properties: {path.relative_to(ROOT)}")
    match = re.search(r"gradle-([0-9.]+)-bin\.zip", path.read_text(encoding="utf-8"))
    if not match:
        fail(f"cannot determine Gradle version from {path.relative_to(ROOT)}")
    return match.group(1)


def validate_build_root(generation: str, build_root: Path, targets: list[str], props: dict[str, str]) -> tuple[str, str]:
    required_files = [
        "settings.gradle.kts", "stonecutter.gradle.kts", "targets.properties",
        "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties", "gradle.properties",
    ]
    for relative in required_files:
        if not (build_root / relative).is_file():
            fail(f"{generation}: missing {build_root.relative_to(ROOT) / relative}")

    settings = (build_root / "settings.gradle.kts").read_text(encoding="utf-8")
    controller = (build_root / "stonecutter.gradle.kts").read_text(encoding="utf-8")
    local = read_properties(build_root / "targets.properties")

    if local.get("generation") != generation:
        fail(f"{generation}: targets.properties declares {local.get('generation')!r}")
    if set(local) - {"generation", "vcsTarget"}:
        fail(f"{generation}: targets.properties must be a selector only; target metadata belongs in build-config/targets.properties")
    if 'file("../..").canonicalFile' not in settings:
        fail(f"{generation}: settings must resolve the repository root independently")
    if 'build-config/targets.properties' not in settings:
        fail(f"{generation}: settings must load the canonical target registry")
    if 'id("dev.kikugie.stonecutter") version ' not in settings:
        fail(f"{generation}: settings must apply a versioned Stonecutter plugin")
    if "kotlinController = true" not in settings:
        fail(f"{generation}: Stonecutter 0.7 requires kotlinController = true")
    if 'target.$targetId.build.generation' not in settings:
        fail(f"{generation}: settings must derive its target list from build.generation")
    if 'tasks.register("buildAndCollect")' not in controller:
        fail(f"{generation}: controller must expose buildAndCollect")
    if 'build-config/targets.properties' not in controller:
        fail(f"{generation}: controller must use the canonical target registry")

    active_match = ACTIVE_PATTERN.search(controller)
    if not active_match:
        fail(f"{generation}: no active Stonecutter target")
    active = active_match.group(1)
    if active not in targets:
        fail(f"{generation}: active target {active!r} is not in {targets}")
    vcs = local.get("vcsTarget", "").strip()
    if vcs not in targets:
        fail(f"{generation}: vcsTarget {vcs!r} is not in {targets}")

    gradle = wrapper_version(build_root)
    if generation == "legacy" and not gradle.startswith("8."):
        fail(f"legacy build must stay on Gradle 8 while ForgeGradle 6 is used, got {gradle}")

    common_adapter = ROOT / "build-logic/common-target.gradle"
    if not common_adapter.is_file():
        fail("missing build-logic/common-target.gradle")
    common_text = common_adapter.read_text(encoding="utf-8")
    for token in (
        "build-config/targets.properties", "familyPlatformSourceRoot",
        "sourceLayers = [sharedSourceRoot, familySourceRoot, platformSourceRoot, familyPlatformSourceRoot, targetOverlayRoot]",
        "scripts/source_preprocessor.py", "scripts/transforms", "prepareEffectiveSource",
    ):
        if token not in common_text:
            fail(f"build-logic/common-target.gradle lacks {token!r}")
    if 'accesstransformer.cfg' in common_text or 'targetAccessTransformer' in common_text:
        fail('build-logic/common-target.gradle must stay loader-neutral; access transformers belong in loader adapters')

    for target in targets:
        if target_build_root(target, props) != build_root.resolve():
            fail(f"{target}: canonical matrix points at the wrong build root")
        layout = target_layout(target, props)
        loader = layout.platform
        wrapper = build_root / f"build.{loader}.gradle"
        adapter = ROOT / "build-logic" / "loaders" / f"{loader}-target.gradle"
        if not wrapper.is_file():
            fail(f"{target}: missing {wrapper.relative_to(ROOT)}")
        if not adapter.is_file():
            fail(f"{target}: missing {adapter.relative_to(ROOT)}")
        wrapper_text = wrapper.read_text(encoding="utf-8")
        adapter_text = adapter.read_text(encoding="utf-8")
        if f"build-logic/loaders/{loader}-target.gradle" not in wrapper_text:
            fail(f"{wrapper.relative_to(ROOT)} must be a thin loader-plugin shim")
        if "build-logic/common-target.gradle" not in adapter_text:
            fail(f"{adapter.relative_to(ROOT)} must use common-target.gradle")
        if "layeredDirs(" in adapter_text:
            fail(f"{adapter.relative_to(ROOT)} still compiles maintained source layers directly")
        if "dependsOn prepareEffectiveSource" not in adapter_text:
            fail(f"{adapter.relative_to(ROOT)} does not gate compilation/resources on preprocessing")

        if loader == "forge":
            if "id 'net.minecraftforge.gradle' version '[6.0,6.2)'" not in wrapper_text:
                fail("legacy Forge build must use ForgeGradle 6.x")
            if "fg.deobf" not in adapter_text:
                fail("Forge adapter must use fg.deobf for mod dependencies")
            if "tasks.findByName('reobfJar')" not in adapter_text:
                fail("Forge adapter must attach reobfJar conditionally")
            if "resolveSourceFile('src/main/resources/META-INF/accesstransformer.cfg')" not in adapter_text:
                fail("Forge adapter must resolve its access transformer from the effective source layers")
            if "accessTransformer = targetAccessTransformer" not in adapter_text:
                fail("Forge adapter must wire its access transformer inside the loader adapter")
        elif loader == "neoforge":
            if "id 'net.neoforged.moddev'" not in wrapper_text:
                fail("NeoForge build must resolve ModDevGradle in its build-root shim")
            if "resolveSourceFile('src/main/resources/META-INF/accesstransformer.cfg')" not in adapter_text:
                fail("NeoForge adapter must resolve its access transformer from the effective source layers")
            if "accessTransformers.from(targetAccessTransformer)" not in adapter_text:
                fail("NeoForge adapter must wire its access transformer inside the loader adapter")

        required = TARGET_REQUIRED + (FORGE_REQUIRED if loader == "forge" else NEOFORGE_REQUIRED if loader == "neoforge" else ())
        for key in required:
            value(props, target, key)
        if value(props, target, "source.family") != generation:
            fail(f"{target}: family must match build generation {generation}")
        if value(props, target, "source.platform") != loader:
            fail(f"{target}: source.platform must match loader {loader}")
        if not layout.family_platform_root.is_dir():
            fail(f"{target}: missing family-platform layer {layout.family_platform_root.relative_to(ROOT)}")

        minecraft = value(props, target, "deps.minecraft")
        if not target.startswith(minecraft + "-"):
            fail(f"{target}: target ID and deps.minecraft disagree")
        for compat in ("jei", "jade"):
            enabled = props.get(f"target.{target}.compat.{compat}.enabled", "true").lower() != "false"
            dep = value(props, target, f"deps.{compat}", allow_empty=True)
            if enabled and not dep:
                fail(f"{target}: {compat} compatibility is enabled without a dependency")

        placeholders = resource_placeholders(target, props)
        block = re.search(r"def\s+resourceProperties\s*=\s*\[(.*?)\n\]", adapter_text, flags=re.DOTALL)
        provided = set(RESOURCE_KEY_PATTERN.findall(block.group(1))) if block else set()
        missing = placeholders - provided
        if missing:
            fail(f"{adapter.relative_to(ROOT)} misses resource placeholders {sorted(missing)}")

    return active, gradle


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--list-targets", action="store_true")
    parser.add_argument("--loader")
    generation_names = tuple(generation_config_paths())
    parser.add_argument("--family", choices=generation_names)
    parser.add_argument("--generation", choices=generation_names)
    args = parser.parse_args()

    props = load_properties()
    targets = target_ids(props)
    generations = generation_targets(props)
    selected_generation = args.generation or args.family

    if args.list_targets:
        for target in targets:
            layout = target_layout(target, props)
            if args.loader and layout.platform != args.loader:
                continue
            if selected_generation and layout.generation != selected_generation:
                continue
            print(target)
        return

    if not TARGETS_PROPERTIES.is_file():
        fail("missing canonical build-config/targets.properties")
    for key in COMMON_REQUIRED:
        if not props.get(f"common.{key}", "").strip():
            fail(f"missing common property common.{key}")
    if props.get("behaviorReference") != "1.19.2-forge":
        fail("behaviorReference must remain 1.19.2-forge")

    for obsolete in (
        "settings.gradle.kts", "stonecutter.gradle.kts", "stonecutter-targets.properties",
        "stonecutter.properties.toml", "build.forge.gradle", "build.neoforge.gradle",
        "gradle.properties", "gradlew", "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
    ):
        if (ROOT / obsolete).exists():
            fail(f"obsolete monolithic Gradle file remains at repository root: {obsolete}")

    for orchestrator in ("build-all.sh", "build-all.bat", "build-all.ps1"):
        if not (ROOT / orchestrator).is_file():
            fail(f"missing repository build orchestrator: {orchestrator}")
    for loader in ("forge", "neoforge", "fabric"):
        if not (ROOT / "build-logic" / "loaders" / f"{loader}-target.gradle").is_file():
            fail(f"missing loader adapter slot: build-logic/loaders/{loader}-target.gradle")

    configs = generation_config_paths()
    reports = []
    for generation, generation_targets_list in generations.items():
        build_root = configs[generation].parent.resolve()
        active, gradle = validate_build_root(generation, build_root, generation_targets_list, props)
        reports.append(f"{generation}: Gradle {gradle}, active={active}, targets={len(generation_targets_list)}")

    required_targets = {"1.19.2-forge", "1.20.1-forge", "1.21.1-neoforge"}
    missing_targets = required_targets - set(targets)
    if missing_targets:
        fail(f"required production targets are missing: {sorted(missing_targets)}")
    if "1.21.1-forge" in targets:
        fail("1.21.1 Forge must not return to the production matrix")

    print("Independent Stonecutter builds OK: " + "; ".join(reports))


if __name__ == "__main__":
    main()
