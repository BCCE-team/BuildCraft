#!/usr/bin/env python3
"""Validate BuildCraft's five-layer shared/family/platform source architecture."""
from __future__ import annotations

from collections import defaultdict
from hashlib import sha256
from pathlib import Path
import json
import re
import sys

from source_layout import (
    effective_source_files,
    ROOT,
    TARGETS_PROPERTIES,
    evaluate_condition,
    family_platform_targets,
    family_targets,
    generation_config_paths,
    generation_targets,
    load_properties,
    platform_targets,
    preprocess_text,
    read_properties,
    target_layout,
    validate_all_directives,
)
from source_preprocessor import source_condition, source_is_enabled, strip_source_condition



def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def warn(message: str) -> None:
    print(f"WARNING: {message}", file=sys.stderr)


def digest(path: Path) -> bytes:
    h = sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.digest()


def file_map(root: Path) -> dict[str, Path]:
    src = root / "src"
    if not src.is_dir():
        return {}
    return {p.relative_to(root).as_posix(): p for p in src.rglob("*") if p.is_file()}


def identical_same_path(left: dict[str, Path], right: dict[str, Path]) -> list[str]:
    return sorted(rel for rel in set(left) & set(right) if digest(left[rel]) == digest(right[rel]))


def validate_canonical_target_registry() -> None:
    if not TARGETS_PROPERTIES.is_file():
        fail("missing build-config/targets.properties canonical target registry")
    canonical = read_properties(TARGETS_PROPERTIES)
    targets = [x.strip() for x in canonical.get("targets", "").split(",") if x.strip()]
    if not targets:
        fail("canonical target registry has no targets")
    for target in targets:
        if canonical.get(f"target.{target}.build.generation", "").strip() not in generation_config_paths():
            fail(f"{target}: canonical target registry has no valid build.generation")

    # Build-root files are selectors only. Per-target values must have one owner.
    props = load_properties()
    if props.get("source.family.modern.canonical_minecraft", "").strip() != "1.21.11":
        fail("modern canonical Java API must remain 1.21.11 until a newer modern target is intentionally promoted")

    for generation, path in generation_config_paths().items():
        local = read_properties(path)
        unexpected = sorted(set(local) - {"generation", "vcsTarget"})
        if unexpected:
            fail(
                f"{path.relative_to(ROOT)} contains target metadata {unexpected[0]!r}; "
                "all per-target values belong in build-config/targets.properties"
            )
        if local.get("generation", "").strip() != generation:
            fail(f"{path.relative_to(ROOT)} must declare generation={generation}")


def validate_loader_boundaries(
    shared_root: Path,
    family_roots: dict[str, Path],
    platform_roots: dict[str, Path],
    family_platform_roots: dict[tuple[str, str], Path],
    family_downport_roots: list[Path],
    family_platform_downport_roots: list[tuple[str, Path]],
) -> None:
    forbidden = ("net.minecraftforge", "net.neoforged", "net.fabricmc")
    for root in [shared_root, *family_roots.values(), *family_downport_roots]:
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="replace")
            for token in forbidden:
                if token in text:
                    fail(f"loader API {token!r} escaped loader layer: {path.relative_to(ROOT)}")

    platform_forbidden = {
        "forge": ("net.neoforged", "net.fabricmc"),
        "neoforge": ("net.minecraftforge", "net.fabricmc"),
        "fabric": ("net.minecraftforge", "net.neoforged"),
    }
    scoped_roots: list[tuple[str, Path]] = list(platform_roots.items())
    scoped_roots.extend((platform, root) for (_family, platform), root in family_platform_roots.items())
    scoped_roots.extend(family_platform_downport_roots)
    for platform, root in scoped_roots:
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="replace")
            for token in platform_forbidden.get(platform, ()):
                if token in text:
                    fail(f"{platform} source imports {token}: {path.relative_to(ROOT)}")

    # A platform-owned Java source must actually depend on that loader. If it does not,
    # it belongs in shared/family ownership and would otherwise recreate loader copies.
    own_loader_tokens = {
        "forge": ("net.minecraftforge",),
        "neoforge": ("net.neoforged",),
        "fabric": ("net.fabricmc",),
    }
    for platform, root in platform_roots.items():
        if not root.exists():
            continue
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="replace")
            if not any(token in text for token in own_loader_tokens.get(platform, ())):
                fail(
                    f"loader-neutral Java stranded in source-platforms/{platform}: "
                    f"{path.relative_to(ROOT)}; promote it to shared/family ownership"
                )



def validate_pure_logic_boundary(shared_root: Path) -> int:
    logic_root = shared_root / "src/main/java/buildcraft/lib/logic"
    if not logic_root.is_dir():
        fail("missing buildcraft.lib.logic pure Java core")
    forbidden = (
        "net.minecraft", "net.minecraftforge", "net.neoforged", "net.fabricmc",
        "com.mojang", "buildcraft.api",
    )
    count = 0
    for path in logic_root.rglob("*.java"):
        count += 1
        text = path.read_text(encoding="utf-8", errors="strict")
        for token in forbidden:
            if token in text:
                fail(f"pure logic depends on {token}: {path.relative_to(ROOT)}")
    if count < 7:
        fail(f"pure Java core unexpectedly small: {count} files")
    return count

def validate_source_selector_policy(
    shared_root: Path,
    family_roots: dict[str, Path],
    platform_roots: dict[str, Path],
    family_platform_roots: dict[tuple[str, str], Path],
    overlays: list[Path],
) -> int:
    """Validate whole-file version selectors used by promoted native implementations."""
    count = 0

    def scan(root: Path, *, allow: bool, label: str) -> None:
        nonlocal count
        if not root.exists():
            return
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".java", ".kt", ".kts", ".gradle", ".json", ".mcmeta", ".toml", ".properties"}:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if "//? source" not in text:
                continue
            condition = source_condition(text)
            if condition is None:
                fail(f"malformed/non-leading source selector in {path.relative_to(ROOT)}")
            count += 1
            if not allow:
                fail(f"whole-file source selector is not allowed in {label}: {path.relative_to(ROOT)}")
            if re.search(r"\b(?:forge|neoforge|fabric)\b", condition):
                fail(
                    f"loader predicate in source selector {path.relative_to(ROOT)}; "
                    "loader selection belongs in platform/family-platform ownership"
                )

    scan(shared_root, allow=False, label="source-shared")
    for family, root in family_roots.items():
        scan(root, allow=True, label=f"source-families/{family}")
    for platform, root in platform_roots.items():
        scan(root, allow=False, label=f"source-platforms/{platform}")
    for (family, platform), root in family_platform_roots.items():
        scan(root, allow=True, label=f"source-family-platforms/{family}/{platform}")
    for overlay in overlays:
        scan(overlay, allow=False, label="version-src")
    return count


def validate_condition_policy(roots: list[Path], overlays: list[Path]) -> tuple[int, int]:
    files = 0
    blocks = 0
    for root in roots:
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8", errors="strict")
            count = len(re.findall(r"^[ \t]*(?://\?|/\*\?)[ \t]*if\b", text, flags=re.MULTILINE))
            if not count:
                continue
            files += 1
            blocks += count
            if count > 4:
                warn(
                    f"{path.relative_to(ROOT)} has {count} inline version branches; "
                    "concentrate repeated version cliffs in compat or explicit downports"
                )
            for line in text.splitlines():
                if re.match(r"^[ \t]*(?://\?|/\*\?)[ \t]*(?:if|}[ \t]*else[ \t]+if)\b", line):
                    if re.search(r"\b(?:forge|neoforge|fabric)\b", line):
                        fail(
                            f"loader condition in maintained source {path.relative_to(ROOT)}; "
                            "loader selection belongs in platform/family-platform ownership"
                        )
    for overlay in overlays:
        for path in overlay.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in {".java", ".json", ".toml", ".mcmeta"}:
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            if "//?" in text or "/*?" in text:
                fail(f"target overlay contains inline conditions: {path.relative_to(ROOT)}")
    return files, blocks


def validate_resource_pipeline_policy(configured_families: list[str], layouts: list) -> tuple[int, int]:
    """Keep versioned resources valid and target resource overlays tiny.

    Resource variants are deliberately stored outside normal source roots: JSON
    must remain valid JSON, while Minecraft-version selection is represented by
    the directory name instead of comments inside the resource itself.
    """
    root = ROOT / "resource-src"
    versioned_files = 0
    json_files = 0
    if root.exists():
        for family_root in sorted(path for path in root.iterdir() if path.is_dir()):
            if family_root.name not in configured_families:
                fail(f"resource-src contains unknown source family: {family_root.relative_to(ROOT)}")
            for version_root in sorted(path for path in family_root.iterdir() if path.is_dir()):
                if not re.fullmatch(r"\d+(?:\.\d+)*", version_root.name):
                    fail(f"resource-src version directory is not a Minecraft version: {version_root.relative_to(ROOT)}")
                for path in version_root.rglob("*"):
                    if not path.is_file():
                        continue
                    versioned_files += 1
                    if path.suffix.lower() in {".json", ".mcmeta"}:
                        json_files += 1
                        text = path.read_text(encoding="utf-8")
                        if "//?" in text or "/*?" in text:
                            fail(f"resource-src must not contain source directives: {path.relative_to(ROOT)}")
                        try:
                            json.loads(text)
                        except json.JSONDecodeError as exc:
                            fail(f"invalid JSON in versioned resource {path.relative_to(ROOT)}: {exc}")

    generated_overlay_prefixes = (
        "src/main/resources/data/forge/tags/fluids/",
        "src/main/resources/assets/buildcraftenergy/models/item/ic2_cell/",
    )
    for layout in layouts:
        overlay = file_map(layout.overlay_root)
        resources = sorted(rel for rel in overlay if rel.startswith("src/main/resources/"))
        if len(resources) > 10:
            fail(
                f"{layout.target}: target resource overlay owns {len(resources)} files; "
                "canonical resources, versioned resource-src or deterministic generators should own them"
            )
        for rel in resources:
            if rel.startswith(generated_overlay_prefixes):
                fail(f"{layout.target}: generated resource leaked into target overlay: {rel}")
            if rel == "src/main/resources/META-INF/mods.toml" and layout.platform == "forge":
                fail(f"{layout.target}: Forge metadata belongs to family-platform ownership, not target overlay")

    return versioned_files, json_files


def validate_preprocessor_contract() -> None:
    if not evaluate_condition(">=26 && fabric && modern", minecraft="26.2", family="modern", platform="fabric"):
        fail("version/loader condition engine rejected a valid future modern Fabric target")
    if evaluate_condition("<1.20", minecraft="1.20.1", family="legacy", platform="forge"):
        fail("version condition engine treats Minecraft 1.20.1 as <1.20")

    sample = """//? if <1.20 {
legacyField
//?} else {
/*?
modernMethod()
?*/
//?}
"""
    legacy = preprocess_text(sample, minecraft="1.19.2", family="legacy", platform="forge")
    modern = preprocess_text(sample, minecraft="1.20.1", family="legacy", platform="forge")
    if legacy.strip() != "legacyField" or modern.strip() != "modernMethod()":
        fail("Stonecutter-style branch activation contract is broken")

    selected = "//? source if >=1.21.11\nclass NativeVariant {}\n"
    if source_is_enabled(selected, minecraft="1.21.1", family="modern", platform="neoforge"):
        fail("whole-file source selector activated before its Minecraft boundary")
    if not source_is_enabled(selected, minecraft="1.21.11", family="modern", platform="neoforge"):
        fail("whole-file source selector did not activate at its Minecraft boundary")
    stripped, condition = strip_source_condition(selected)
    if condition != ">=1.21.11" or stripped != "class NativeVariant {}\n":
        fail("whole-file source selector stripping contract is broken")


def validate_materializer_boundary() -> None:
    path = ROOT / "scripts/source_layout.py"
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if len(lines) > 400:
        fail(f"source_layout.py grew to {len(lines)} lines; keep it as a generic materializer")
    forbidden = (
        "text.replace(", "re.sub(", "relative.endswith(\"buildcraft/",
        "TileTank", "EntityRobot", "GuiBC8", "RenderEngine",
    )
    for token in forbidden:
        if token in text:
            fail(f"source_layout.py contains class/API rewrite token {token!r}; move it to transforms or maintained source")



def validate_build_logic_uses_effective_sources() -> None:
    path = ROOT / "build-logic/loaders/neoforge-target.gradle"
    text = path.read_text(encoding="utf-8")
    forbidden = (
        "new File(targetOverlayRoot, 'src/main/java/buildcraft/core/client/RenderTickListener.java')",
        "sourceSets.main.java.exclude 'buildcraft/core/client/RenderTickListener.java'",
    )
    for token in forbidden:
        if token in text:
            fail(
                "NeoForge build logic bypasses the five-layer materializer for RenderTickListener; "
                "compile sources must follow the effective-source tree"
            )


def main() -> None:
    validate_canonical_target_registry()
    props = load_properties()
    configured_families = [x.strip() for x in props.get("sourceFamilies", "").split(",") if x.strip()]
    if configured_families != ["legacy", "modern"]:
        fail(f"sourceFamilies must be legacy,modern; got {configured_families}")

    generations = generation_targets(props)
    required_legacy = {"1.19.2-forge", "1.20.1-forge"}
    if not required_legacy.issubset(generations.get("legacy", [])):
        fail(f"legacy build generation is missing reference targets: {generations.get('legacy')}")
    if "1.21.1-neoforge" not in generations.get("modern", []):
        fail(f"modern build generation must contain 1.21.1-neoforge: {generations.get('modern')}")
    if "1.21.1-forge" in {target for values in generations.values() for target in values}:
        fail("1.21.1 Forge must not return to production source generations")
    if props.get("behaviorReference") != "1.19.2-forge":
        fail("behaviorReference must remain 1.19.2-forge")

    layouts = [target_layout(target, props) for targets in generations.values() for target in targets]
    family_downport_roots = sorted({
        layout.family_downport_root for layout in layouts if layout.family_downport_root is not None
    })
    family_platform_downport_roots = sorted({
        (layout.platform, layout.family_platform_downport_root)
        for layout in layouts if layout.family_platform_downport_root is not None
    }, key=lambda item: (item[0], str(item[1])))
    for layout in layouts:
        for layer in layout.layers:
            if not layer.is_dir():
                fail(f"{layout.target}: missing source layer {layer.relative_to(ROOT)}")
        effective_source_files(layout, props)

    resource_variant_files, resource_variant_json = validate_resource_pipeline_policy(
        configured_families, layouts
    )

    shared_roots = {layout.shared_root for layout in layouts}
    if len(shared_roots) != 1:
        fail("all targets must use one global shared source root")
    shared_root = next(iter(shared_roots))
    shared_map = file_map(shared_root)

    family_roots: dict[str, Path] = {}
    for family, targets in family_targets(props).items():
        roots = {target_layout(target, props).family_root for target in targets}
        if len(roots) != 1:
            fail(f"{family}: targets use multiple family roots")
        family_roots[family] = next(iter(roots))

    platform_roots: dict[str, Path] = {}
    for platform, targets in platform_targets(props).items():
        roots = {target_layout(target, props).platform_root for target in targets}
        if len(roots) != 1:
            fail(f"{platform}: targets use multiple platform roots")
        platform_roots[platform] = next(iter(roots))

    family_platform_roots: dict[tuple[str, str], Path] = {}
    for key, targets in family_platform_targets(props).items():
        roots = {target_layout(target, props).family_platform_root for target in targets}
        if len(roots) != 1:
            fail(f"{key[0]}/{key[1]}: targets use multiple family-platform roots")
        family_platform_roots[key] = next(iter(roots))

    family_maps = {name: file_map(root) for name, root in family_roots.items()}
    platform_maps = {name: file_map(root) for name, root in platform_roots.items()}
    family_platform_maps = {key: file_map(root) for key, root in family_platform_roots.items()}
    overlay_maps = {layout.target: file_map(layout.overlay_root) for layout in layouts}

    for family, files in family_maps.items():
        redundant = identical_same_path(shared_map, files)
        if redundant:
            fail(f"family/{family}: byte-identical override duplicates source-shared; first: {redundant[0]}")

    platform_to_layouts: dict[str, list] = defaultdict(list)
    for layout in layouts:
        platform_to_layouts[layout.platform].append(layout)
    for platform, files in platform_maps.items():
        redundant: list[str] = []
        for relative, platform_path in files.items():
            compared = False
            for layout in platform_to_layouts[platform]:
                lower = next(
                    (candidate for root in (layout.family_root, layout.shared_root)
                     if (candidate := root / relative).is_file()),
                    None,
                )
                if lower is None or digest(lower) != digest(platform_path):
                    break
                compared = True
            else:
                if compared:
                    redundant.append(relative)
        if redundant:
            fail(f"platform/{platform}: byte-identical override duplicates every lower layer; first: {sorted(redundant)[0]}")

    for (family, platform), files in family_platform_maps.items():
        layout = next(l for l in layouts if l.family == family and l.platform == platform)
        redundant: list[str] = []
        for relative, fp_path in files.items():
            lower = next(
                (candidate for root in (layout.platform_root, layout.family_root, layout.shared_root)
                 if (candidate := root / relative).is_file()),
                None,
            )
            if lower is not None and digest(lower) == digest(fp_path):
                redundant.append(relative)
        if redundant:
            fail(
                f"family-platform/{family}/{platform}: byte-identical override duplicates lower layer; "
                f"first: {sorted(redundant)[0]}"
            )

    for layout in layouts:
        redundant: list[str] = []
        for relative, overlay_path in overlay_maps[layout.target].items():
            lower = next(
                (candidate for root in (
                    layout.family_platform_root,
                    layout.platform_root,
                    layout.family_root,
                    layout.shared_root,
                ) if (candidate := root / relative).is_file()),
                None,
            )
            if lower is not None and digest(lower) == digest(overlay_path):
                redundant.append(relative)
        if redundant:
            fail(
                f"{layout.target}: target overlay contains a byte-identical lower-layer override; "
                f"first: {sorted(redundant)[0]}"
            )

    escaped_global = identical_same_path(family_maps["legacy"], family_maps["modern"])
    if escaped_global:
        fail(
            f"{len(escaped_global)} identical cross-family files escaped source-shared; "
            f"first: {escaped_global[0]}"
        )

    groups: dict[tuple[str, str], list[str]] = defaultdict(list)
    for layout in layouts:
        groups[(layout.family, layout.platform)].append(layout.target)
    for (family, platform), targets in groups.items():
        if len(targets) < 2:
            continue
        common_paths = set.intersection(*(set(overlay_maps[target]) for target in targets))
        escaped = [
            rel for rel in sorted(common_paths)
            if len({digest(overlay_maps[target][rel]) for target in targets}) == 1
        ]
        if escaped:
            fail(
                f"{family}/{platform}: {len(escaped)} identical files remain in every target overlay; "
                f"move them to source-family-platforms/{family}/{platform} (first: {escaped[0]})"
            )

    validate_loader_boundaries(
        shared_root, family_roots, platform_roots, family_platform_roots,
        family_downport_roots, family_platform_downport_roots,
    )
    pure_logic_files = validate_pure_logic_boundary(shared_root)
    source_variants = validate_source_selector_policy(
        shared_root,
        family_roots,
        platform_roots,
        family_platform_roots,
        [layout.overlay_root for layout in layouts],
    )
    # Downports are explicit complete older-version implementations, not another
    # conditional language. Keep them boring and deterministic.
    for root in family_downport_roots:
        for path in root.rglob("*"):
            if path.is_file() and path.suffix.lower() in {".java", ".json", ".properties"}:
                text = path.read_text(encoding="utf-8", errors="replace")
                if "//?" in text or "/*?" in text:
                    fail(f"downport source contains conditional directives: {path.relative_to(ROOT)}")
    for _platform, root in family_platform_downport_roots:
        for path in root.rglob("*"):
            if path.is_file() and path.suffix.lower() in {".java", ".json", ".properties"}:
                text = path.read_text(encoding="utf-8", errors="replace")
                if "//?" in text or "/*?" in text:
                    fail(f"downport source contains conditional directives: {path.relative_to(ROOT)}")

    validate_preprocessor_contract()
    validate_materializer_boundary()
    validate_build_logic_uses_effective_sources()
    validate_all_directives(props)
    condition_files, condition_blocks = validate_condition_policy(
        [shared_root, *family_roots.values(), *platform_roots.values(), *family_platform_roots.values()],
        [layout.overlay_root for layout in layouts],
    )

    # Modern target overlays are emergency escape hatches only. The current
    # 1.21.11 target is allowed one frozen API file because API work is explicitly
    # outside the source-family architecture rules enforced by this validator.
    for layout in layouts:
        java_paths = sorted(rel for rel in overlay_maps[layout.target] if rel.endswith(".java"))
        if layout.family != "modern":
            continue
        if len(java_paths) > 10:
            fail(f"{layout.target}: modern target overlay owns {len(java_paths)} Java files; budget is <=10")
        if layout.target == "1.21.11-neoforge":
            allowed = {"src/main/java/buildcraft/api/v2/recipe/CountedIngredient.java"}
            unexpected = [rel for rel in java_paths if rel not in allowed]
            if unexpected:
                fail(f"1.21.11 target Java escaped canonical/downport ownership: {unexpected[0]}")

    bootstrap = ROOT / "scripts/transforms/bootstrap_12111.py"
    if bootstrap.exists():
        fail("target-specific scripts/transforms/bootstrap_12111.py is forbidden; use path-independent transforms")
    java_compat = ROOT / "scripts/transforms/java_compat.py"
    if not java_compat.is_file():
        fail("missing path-independent Java compatibility transform")
    compat_text = java_compat.read_text(encoding="utf-8")
    if re.search(r"buildcraft/[^\"']+\.java", compat_text):
        fail("Java transform names a BuildCraft source path; class-specific rewrites belong in maintained source")
    path_checks = [m.group(0) for m in re.finditer(r"relative\.endswith\([^\n]+", compat_text)]
    if any('.java' not in check or 'relative.endswith(".java")' not in check for check in path_checks):
        fail("Java transform contains a path-specific relative.endswith branch")

    physical_files = (
        len(shared_map)
        + sum(len(values) for values in family_maps.values())
        + sum(len(values) for values in platform_maps.values())
        + sum(len(values) for values in family_platform_maps.values())
        + sum(len(file_map(root)) for root in family_downport_roots)
        + sum(len(file_map(root)) for _platform, root in family_platform_downport_roots)
        + sum(len(values) for values in overlay_maps.values())
    )
    effective_files = sum(len(effective_source_files(layout, props)) for layout in layouts)
    saved = effective_files - physical_files
    reduction = saved / effective_files * 100 if effective_files else 0.0

    print(
        "Five-layer source layout OK: "
        f"shared={len(shared_map)}, "
        + ", ".join(f"family/{name}={len(files)}" for name, files in family_maps.items())
        + ", "
        + ", ".join(f"platform/{name}={len(files)}" for name, files in platform_maps.items())
        + ", "
        + ", ".join(
            f"family-platform/{family}/{platform}={len(files)}"
            for (family, platform), files in family_platform_maps.items()
        )
    )
    print("Target overlays: " + ", ".join(f"{target}={len(files)}" for target, files in overlay_maps.items()))
    print(
        "Downport views: "
        + ", ".join(str(root.relative_to(ROOT)) + "=" + str(len(file_map(root))) for root in family_downport_roots)
        + (", " if family_downport_roots and family_platform_downport_roots else "")
        + ", ".join(str(root.relative_to(ROOT)) + "=" + str(len(file_map(root))) for _platform, root in family_platform_downport_roots)
    )
    print(
        f"Source variants: {source_variants}; pure Java logic: {pure_logic_files} files; "
        f"inline version conditions: {condition_files} files / {condition_blocks} blocks; "
        f"physical files={physical_files}, effective files={effective_files}, "
        f"duplicate copies eliminated={saved} ({reduction:.1f}%)"
    )


if __name__ == "__main__":
    main()
