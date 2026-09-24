#!/usr/bin/env python3
"""Cross-target resource, metadata, build-hygiene and hotspot validation.

This catches classes of regressions that compile/parity-count checks miss:
case-sensitive atlas/model resources, target metadata drift, stale datagen output,
translation ownership, stack-size parity, pipe recipe schemas, transfer-profile wiring,
and copied target implementations that silently lose fixes on one maintained Minecraft line.
"""
from __future__ import annotations

import json
import re
import sys
import tempfile
from collections import Counter
from pathlib import Path

from source_layout import ROOT, load_properties, materialize_target, preprocess_text, resolve_effective_source, target_ids, target_layout
from source_preprocessor import strip_source_condition


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


_RESOURCE_TEMP = tempfile.TemporaryDirectory(prefix="bc-cross-target-resources-")
_RESOURCE_CACHE: dict[str, dict[str, Path]] = {}


def resource_map(target: str, props: dict[str, str]) -> dict[str, Path]:
    """Return the actual materialized resource pack, including generated files.

    Resources are materialized through a pipeline rather than a raw source-tree merge.
    Validators therefore inspect the effective pack after selectors,
    transforms and deterministic generators have run.
    """
    cached = _RESOURCE_CACHE.get(target)
    if cached is not None:
        return cached
    root = Path(_RESOURCE_TEMP.name) / target
    materialize_target(target, root, props)
    resources = root / "src/main/resources"
    result = {
        path.relative_to(resources).as_posix(): path
        for path in resources.rglob("*")
        if path.is_file()
    }
    _RESOURCE_CACHE[target] = result
    return result


def effective_java(target: str, rel: str, props: dict[str, str]) -> str:
    layout = target_layout(target, props)
    logical = Path("src/main/java") / rel
    path = resolve_effective_source(layout, props, logical)
    if path is None:
        fail(f"{target}: missing effective Java source {logical.as_posix()}")
    minecraft = props[f"target.{target}.deps.minecraft"]
    source, _selector = strip_source_condition(path.read_text(encoding="utf-8"))
    return preprocess_text(
        source,
        minecraft=minecraft,
        family=layout.family,
        platform=layout.platform,
    )


def validate_atlases_and_case(props: dict[str, str]) -> None:
    for target in target_ids(props):
        resources = resource_map(target, props)

        # Minecraft resource locations are lowercase-only. Checking the effective
        # pack itself catches stale camelCase files even when no current registry
        # entry references them (1.21 logs these as "Invalid path in pack").
        bad_asset_paths = sorted(
            rel for rel in resources
            if rel.startswith("assets/") and rel != rel.lower()
        )
        if bad_asset_paths:
            fail(f"{target}: non-lowercase asset path remains: {bad_asset_paths[0]}")

        atlas_rel = "assets/minecraft/atlases/blocks.json"
        if atlas_rel in resources:
            atlas = json.loads(resources[atlas_rel].read_text(encoding="utf-8"))
            lower = {rel.lower(): rel for rel in resources}
            for index, source in enumerate(atlas.get("sources", [])):
                if not isinstance(source, dict) or source.get("type") != "minecraft:single":
                    continue
                resource = source.get("resource")
                if not isinstance(resource, str) or ":" not in resource:
                    fail(f"{target}: atlas single source #{index} has invalid resource {resource!r}")
                if resource != resource.lower():
                    fail(f"{target}: atlas resource is not lowercase: {resource}")
                namespace, path = resource.split(":", 1)
                if not namespace.startswith("buildcraft"):
                    continue
                texture_rel = f"assets/{namespace}/textures/{path}.png"
                if texture_rel not in resources:
                    ci = lower.get(texture_rel.lower())
                    suffix = f" (case-only candidate: {ci})" if ci else ""
                    fail(f"{target}: atlas resource does not exist exactly: {resource}{suffix}")
                sprite = source.get("sprite")
                if sprite is not None:
                    if not isinstance(sprite, str) or sprite != sprite.lower():
                        fail(f"{target}: atlas sprite id is not lowercase: {sprite!r}")

        lower = {rel.lower(): rel for rel in resources}

        def validate_vanilla_element(rel: str, value) -> None:
            if isinstance(value, dict):
                for key in ("from", "to"):
                    coords = value.get(key)
                    if isinstance(coords, list) and any(not isinstance(v, (int, float)) for v in coords):
                        fail(
                            f"{target}: BuildCraft variable-model expression leaked into vanilla model "
                            f"{rel}: {key}={coords!r}"
                        )
                rotation = value.get("rotation")
                if isinstance(rotation, (int, float)) and rotation not in (0, 90, 180, 270):
                    fail(f"{target}: invalid vanilla model face rotation in {rel}: {rotation}")
                for child in value.values():
                    validate_vanilla_element(rel, child)
            elif isinstance(value, list):
                for child in value:
                    validate_vanilla_element(rel, child)

        # BuildCraft's variable-model DSL is parsed by ModelHolderVariable and must
        # live below assets/<namespace>/bcmodels, never the vanilla models tree.
        # Newer clients enumerate model JSON eagerly and otherwise try to parse DSL
        # expressions such as "pos" or "y1 * 12" as vanilla floats.
        for rel, model_path in resources.items():
            if not rel.startswith("assets/") or "/models/" not in rel or not rel.endswith(".json"):
                continue
            try:
                model = json.loads(model_path.read_text(encoding="utf-8"))
            except Exception as exc:
                fail(f"{target}: invalid model JSON {rel}: {exc}")
            if not isinstance(model, dict):
                continue
            leaked = [key for key in ("variables", "rules", "inlines") if key in model]
            if leaked:
                fail(f"{target}: BuildCraft variable-model DSL leaked into vanilla model {rel}: {leaked}")
            validate_vanilla_element(rel, model)

            parent = model.get("parent")
            if isinstance(parent, str) and ":" in parent:
                namespace, path = parent.split(":", 1)
                if namespace.startswith("buildcraft") and path != path.lower():
                    fail(f"{target}: uppercase BuildCraft model parent in {rel}: {parent}")

            # Validate exact-case BuildCraft texture references in every vanilla
            # model, not only item models.
            textures = model.get("textures")
            if not isinstance(textures, dict):
                continue
            for value in textures.values():
                if not isinstance(value, str) or value.startswith("#") or ":" not in value:
                    continue
                namespace, path = value.split(":", 1)
                if not namespace.startswith("buildcraft"):
                    continue
                if path != path.lower():
                    fail(f"{target}: uppercase BuildCraft texture id in {rel}: {value}")
                texture_rel = f"assets/{namespace}/textures/{path}.png"
                if texture_rel in resources:
                    continue
                ci = lower.get(texture_rel.lower())
                suffix = f" (case-only candidate: {ci})" if ci else ""
                fail(f"{target}: model texture does not exist exactly in {rel}: {value}{suffix}")

        # All variable models actually consumed by the runtime must stay in the
        # dedicated non-vanilla resource directory on every target.
        for required in (
            "assets/buildcraftfactory/bcmodels/tiles/distiller.json",
            "assets/buildcraftsilicon/bcmodels/plugs/gate.json",
            "assets/buildcraftsilicon/bcmodels/plugs/gate_dynamic.json",
            "assets/buildcraftsilicon/bcmodels/plugs/pulsar_dynamic.json",
            "assets/buildcraftsilicon/bcmodels/plugs/lens.json",
            "assets/buildcraftsilicon/bcmodels/plugs/filter.json",
            "assets/buildcrafttransport/bcmodels/pipes/stripes.json",
        ):
            if required not in resources:
                fail(f"{target}: missing BuildCraft variable-model resource {required}")

        # Every registered FE pipe is an item and therefore needs a vanilla item-model
        # declaration even though BuildCraft supplies the actual pipe geometry at runtime.
        # Missing declarations are reported by ModelBakery only on the client.
        for pipe_name in (
            "wood_fe", "stone_fe", "cobblestone_fe", "sandstone_fe", "quartz_fe",
            "gold_fe", "iron_fe", "diamond_fe", "diamond_wood_fe",
        ):
            model_rel = f"assets/buildcrafttransport/models/item/{pipe_name}.json"
            if model_rel not in resources:
                fail(f"{target}: missing FE pipe item model declaration {model_rel}")

        # 1.20+ uses an explicit block atlas. All limiter sprites referenced by
        # BCTransportSprites must be stitched, including FE and the zero-limit icon.
        if atlas_rel in resources:
            atlas = json.loads(resources[atlas_rel].read_text(encoding="utf-8"))
            stitched = {
                source.get("resource")
                for source in atlas.get("sources", [])
                if isinstance(source, dict) and source.get("type") == "minecraft:single"
            }
            for prefix in ("trigger_limiter", "trigger_fe_limiter"):
                for shift in (256, 128, 64, 32, 16, 8, 4, 2, 0):
                    sprite = f"buildcrafttransport:triggers/{prefix}_m{shift}"
                    if sprite not in stitched:
                        fail(f"{target}: limiter sprite is not stitched into the block atlas: {sprite}")

        holder_expectations = {
            "buildcraft/factory/BCFactoryModels.java": "buildcraftfactory:bcmodels/tiles/distiller.json",
            "buildcraft/silicon/BCSiliconModels.java": ":bcmodels/",
            "buildcraft/transport/BCTransportModels.java": "buildcrafttransport:bcmodels/",
        }
        for rel, token in holder_expectations.items():
            java = effective_java(target, rel, props)
            if token not in java:
                fail(f"{target}: variable-model holder {rel} is not isolated below bcmodels ({token!r})")


def validate_stack_parity(props: dict[str, str]) -> None:
    builders_rel = "buildcraft/builders/BCBuildersItems.java"
    core_rel = "buildcraft/core/BCCoreItems.java"
    for target in target_ids(props):
        builders = effective_java(target, builders_rel, props)
        core = effective_java(target, core_rel, props)
        lines = {line.strip() for line in builders.splitlines()}
        registrations = {
            "blueprint": next((line for line in lines if 'register("blueprint"' in line), ""),
            "template": next((line for line in lines if 'register("template"' in line), ""),
            "schematic_single": next((line for line in lines if 'register("schematic_single"' in line), ""),
            "map_location": next((line.strip() for line in core.splitlines() if 'register("map_location"' in line), ""),
        }
        for name, line in registrations.items():
            if not line:
                fail(f"{target}: cannot find {name} registration")
            if ".stacksTo(16)" not in line:
                fail(
                    f"{target}: blank/clean {name} must have registration-level stack size 16: "
                    f"{line}"
                )

        minecraft = props[f"target.{target}.deps.minecraft"]
        if minecraft.startswith("1.21"):
            map_type = effective_java(target, "buildcraft/core/item/MapLocationType.java", props)
            snapshot = effective_java(target, "buildcraft/builders/item/ItemSnapshot.java", props)
            schematic_item = effective_java(target, "buildcraft/builders/item/ItemSchematicSingle.java", props)
            if "DataComponents.MAX_STACK_SIZE, this == CLEAN ? 16 : 1" not in map_type:
                fail(f"{target}: map_location state transitions must enforce clean=16 and recorded=1")
            if "stack.set(DataComponents.MAX_STACK_SIZE, 1);" not in snapshot:
                fail(f"{target}: used blueprint/template snapshots must enforce stack size 1")
            if schematic_item.count("stack.set(DataComponents.MAX_STACK_SIZE, 1);") < 1:
                fail(f"{target}: used schematic_single must enforce stack size 1")
            if "stack.set(DataComponents.MAX_STACK_SIZE, 16);" not in schematic_item:
                fail(f"{target}: cleared schematic_single must restore stack size 16")
        else:
            map_item = effective_java(target, "buildcraft/core/item/ItemMapLocation.java", props)
            snapshot = effective_java(target, "buildcraft/builders/item/ItemSnapshot.java", props)
            schematic_item = effective_java(target, "buildcraft/builders/item/ItemSchematicSingle.java", props)
            if "MapLocationType.getFromStack(stack) == MapLocationType.CLEAN ? 16 : 1" not in map_item:
                fail(f"{target}: map_location dynamic stack size must be clean=16 and recorded=1")
            if "EnumItemSnapshotType.getFromStack(stack).used ? 1 : 16" not in snapshot:
                fail(f"{target}: blueprint/template dynamic stack size must be clean=16 and used=1")
            if "isUsed(stack) ? 1 : 16" not in schematic_item:
                fail(f"{target}: schematic_single dynamic stack size must be blank=16 and used=1")


def validate_machine_fluid_drop_ownership(props: dict[str, str]) -> None:
    for target in target_ids(props):
        for rel, name in (
            ("buildcraft/factory/tile/TilePump.java", "Pump"),
            ("buildcraft/factory/tile/TileFloodGate.java", "Flood Gate"),
        ):
            java = effective_java(target, rel, props)
            if "tankManager.addLast(tank);" not in java:
                fail(f"{target}: {name} tank is no longer owned by tankManager")
            if "FluidDropRuntime.addFluidDrops(toDrop, tank);" in java:
                fail(
                    f"{target}: {name} manually drops its managed tank before super.addDrops, "
                    "which duplicates fluid drops"
                )
            if "super.addDrops(toDrop, fortune);" not in java:
                fail(f"{target}: {name} must delegate managed tank drops to TileBC_Neptune")


def validate_metadata(props: dict[str, str]) -> None:
    for target in target_ids(props):
        version = props[f"target.{target}.deps.minecraft"]
        parts = [int(x) for x in version.split(".")]
        if len(parts) != 3:
            fail(f"{target}: expected x.y.z Minecraft version, got {version}")
        upper = f"{parts[0]}.{parts[1]}.{parts[2] + 1}"
        expected = f"[{version},{upper})"
        actual = props.get(f"target.{target}.minecraft.version_range")
        if actual != expected:
            fail(f"{target}: Minecraft range must be exact patch line {expected}, got {actual}")


def validate_datagen_isolation() -> None:
    for rel in ("build-logic/loaders/forge-target.gradle", "build-logic/loaders/neoforge-target.gradle"):
        text = (ROOT / rel).read_text(encoding="utf-8")
        if 'new File(repositoryRoot, "build/generated-resources/' in text:
            fail(f"{rel}: datagen output still lives outside the target build directory")
        if "sourceSets.main.resources.srcDir(generatedResources)" in text:
            fail(f"{rel}: stale datagen output is still part of production main resources")
        for token in (
            'layout.buildDirectory.dir("generated-resources/datagen")',
            "project.delete(generatedResources)",
            "generatedResources.mkdirs()",
        ):
            if token not in text:
                fail(f"{rel}: missing datagen hygiene guard {token!r}")


def validate_language_policy(props: dict[str, str]) -> None:
    # The main mod owns English only. Every other locale belongs to the separate
    # localization addon and must never leak back into production source layers.
    en_us_files: list[Path] = []
    for base in (ROOT / "source-shared", ROOT / "source-families", ROOT / "source-platforms", ROOT / "source-family-platforms", ROOT / "version-src"):
        en_us_files.extend(p for p in base.rglob("src/main/resources/assets/*/lang/en_us.json") if p.is_file())
    expected = ROOT / "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json"
    unexpected = sorted(p.relative_to(ROOT).as_posix() for p in en_us_files if p != expected)
    if unexpected:
        fail(f"en_us must have one authoritative source; first extra: {unexpected[0]}")
    if not expected.is_file():
        fail("missing authoritative source-shared BuildCraft en_us.json")

    for base in (ROOT / "source-shared", ROOT / "source-families", ROOT / "source-platforms", ROOT / "source-family-platforms", ROOT / "version-src"):
        for path in base.rglob("src/main/resources/assets/*/lang/*.json"):
            if path.is_file() and path.name != "en_us.json":
                fail(f"main mod must contain en_us only; addon-owned locale remains: {path.relative_to(ROOT)}")
        for path in base.rglob("src/main/resources/assets/*/guide/text/*.json"):
            if path.is_file() and path.name != "en_us.json":
                fail(f"main mod guide must contain en_us only; addon-owned locale remains: {path.relative_to(ROOT)}")

    duplicates: list[str] = []
    def hook(pairs):
        seen = set()
        out = {}
        for key, value in pairs:
            if key in seen:
                duplicates.append(key)
            seen.add(key)
            out[key] = value
        return out
    json.loads(expected.read_text(encoding="utf-8"), object_pairs_hook=hook)
    if duplicates:
        fail(f"duplicate translation key inside en_us.json: {duplicates[0]}")

    for target in target_ids(props):
        resources = resource_map(target, props)
        locale_files: dict[str, list[tuple[str, Path]]] = {}
        for rel, path in resources.items():
            match = re.fullmatch(r"assets/[^/]+/lang/([^/]+\.json)", rel)
            if match:
                locale_files.setdefault(match.group(1), []).append((rel, path))
        for locale, files in locale_files.items():
            owners: dict[str, str] = {}
            for rel, path in sorted(files):
                data = json.loads(path.read_text(encoding="utf-8"))
                for key in data:
                    previous = owners.get(key)
                    if previous is not None:
                        fail(f"{target}: duplicate {locale} translation key {key!r} in {previous} and {rel}")
                    owners[key] = rel


def validate_guide_resources() -> None:
    names = {
        "cover.png", "icons.png", "left_page.png", "left_page_back.png",
        "left_page_first.png", "note.png", "right_page.png", "right_page_back.png",
        "right_page_last.png",
    }
    shared = ROOT / "source-shared/src/main/resources/assets/buildcraftlib/textures/gui/guide"
    missing = sorted(name for name in names if not (shared / name).is_file())
    if missing:
        fail(f"shared guide textures incomplete: {missing}")
    for base in (
        ROOT / "source-families/modern/src/main/resources/assets/buildcraftlib/textures/gui/guide",
        ROOT / "version-src/1.20.1-forge/src/main/resources/assets/buildcraftlib/textures/gui/guide",
    ):
        leftovers = [p.name for p in base.glob("*.png")] if base.is_dir() else []
        if leftovers:
            fail(f"redundant modern guide texture remains in {base.relative_to(ROOT)}: {leftovers[0]}")


def validate_quartz_sprite_paths() -> None:
    bad_patterns = (
        'new ResourceLocation("quartz_block_top")',
        'ResourceLocation.withDefaultNamespace("quartz_block_top")',
        'ResourceLocation.parse("quartz_block_top")',
    )
    for base in (ROOT / "source-shared", ROOT / "source-families", ROOT / "source-platforms", ROOT / "source-family-platforms", ROOT / "version-src"):
        for path in base.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            for bad in bad_patterns:
                if bad in text:
                    fail(f"wrong vanilla quartz sprite path remains in {path.relative_to(ROOT)}: {bad}")


def validate_promoted_cross_generation_files() -> None:
    manifest = ROOT / "build-config/promoted-cross-generation-files.txt"
    if not manifest.is_file():
        fail("missing promoted cross-generation file manifest")
    rels = [line.strip() for line in manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rels:
        fail("promoted cross-generation file manifest is empty")
    for rel in rels:
        shared = ROOT / "source-shared/src/main" / rel
        old20 = ROOT / "version-src/1.20.1-forge/src/main" / rel
        old21 = ROOT / "source-families/modern/src/main" / rel
        if not shared.is_file():
            fail(f"promoted cross-generation source missing from shared: {rel}")
        if old20.exists():
            fail(f"promoted source was copied back into 1.20.1 target layer: {rel}")
        if old21.exists():
            first = old21.read_text(encoding="utf-8").splitlines()[0].strip()
            # 1.21.11 is the canonical modern Java API. A promoted 1.20.1/1.21.1
            # shared source may therefore gain a >=1.21.11 family
            # variant without shadowing the shared implementation on 1.21.1.
            if first != "//? source if >=1.21.11":
                fail(f"promoted source was copied back into modern family without a 1.21.11 selector: {rel}")


def validate_hotspots(props: dict[str, str]) -> None:
    common = {
        "buildcraft/energy/client/gui/GuiEngineFE.java": (
            "RenderSystem.enableBlend();", "RenderSystem.defaultBlendFunc();", "OVERLAY.drawAt",
        ),
        "buildcraft/energy/client/gui/GuiDynamoMJ.java": (
            "RenderSystem.enableBlend();", "RenderSystem.defaultBlendFunc();", "OVERLAY.drawAt",
        ),
        "buildcraft/transport/client/model/PipeBaseModelGenStandard.java": (
            "loadSpritesCache", "generateTranslucent", "getPipeModelColour",
        ),
        "buildcraft/energy/BCEnergySprites.java": (
            "FE_BACK_R", "FE_SIDE_R", "DYNAMO_MJ_GUI",
        ),
        "buildcraft/factory/tile/TileMiningWell.java": (
            "isStandaloneWaterBlock", "api2MachineTypeId",
        ),
    }
    for target in target_ids(props):
        for rel, tokens in common.items():
            text = effective_java(target, rel, props)
            for token in tokens:
                if token not in text:
                    fail(f"{target}: protected cross-target hotspot {rel} lost {token!r}")

        zone_gui = effective_java(target, "buildcraft/robotics/gui/GuiZonePlanner.java", props)
        zone_map = effective_java(target, "buildcraft/robotics/zone/ZonePlannerMapChunk.java", props)
        for token in ("Heightmap.Types.WORLD_SURFACE", "getChunkNow(key.chunkPos.x, key.chunkPos.z - 1)"):
            if token not in zone_map:
                fail(f"{target}: protected Zone Planner hotspot lost {token!r}")
        if target == "1.21.11-neoforge":
            if "fillNativeImage(" not in zone_gui or "argbToAbgr(colour)" in zone_gui:
                fail(f"{target}: Zone Planner GUI must write the native 1.21.11 ARGB value directly")
            for token in ("calculateARGBColor(brightness)", "new MapColourData(current.posY, mapColour)"):
                if token not in zone_map:
                    fail(f"{target}: Zone Planner native ARGB path lost {token!r}")
        else:
            if "argbToAbgr(colour)" not in zone_gui:
                fail(f"{target}: Zone Planner GUI lost the pre-1.21.11 ARGB->ABGR conversion")
            if "mapNativeToArgb(nativeMapColour)" not in zone_map:
                fail(f"{target}: Zone Planner map cache lost the pre-1.21.11 colour conversion")



def validate_pipe_recipe_schema(props: dict[str, str]) -> None:
    """Custom PipeRecipe uses result.item on every maintained target.

    Minecraft 1.21's codec errors are easy to miss during static JSON counting because
    the JSON remains syntactically valid. Validate the serializer contract explicitly.
    """
    for target in target_ids(props):
        resources = resource_map(target, props)
        for rel, path in resources.items():
            if not rel.startswith("data/buildcrafttransport/") or not rel.endswith(".json"):
                continue
            if "/recipe/" not in rel and "/recipes/" not in rel:
                continue
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except Exception as exc:
                fail(f"{target}: invalid recipe JSON {rel}: {exc}")
            if data.get("type") != "buildcrafttransport:pipe":
                continue
            result = data.get("result")
            if not isinstance(result, dict):
                fail(f"{target}: custom pipe recipe {rel} has no object result")
            if "item" not in result:
                fail(f"{target}: custom pipe recipe {rel} must encode result.item, got {result}")
            if "id" in result:
                fail(f"{target}: custom pipe recipe {rel} still uses unsupported result.id")


def validate_pipe_transfer_wiring() -> None:
    bridge_rel = "source-shared/src/main/java/buildcraft/transport/api2/PipeTypeBridge.java"
    bridge = (ROOT / bridge_rel).read_text(encoding="utf-8")
    for token in (
        'DEFAULT_FLUID_BASE_RATE = 10',
        'DEFAULT_MJ_BASE_RATE = 4',
        'DEFAULT_FE_BASE_RATE = 40',
        'case "wood_fluid", "cobblestone_fluid"',
        'case "wood_power" -> { multiplier = 4; resistanceDivisor = 128; extractor = true; }',
        'case "wood_fe" -> { multiplier = 4; extractor = true; }',
    ):
        if token not in bridge:
            fail(f"{bridge_rel}: built-in API2 defaults lost {token!r}")

    for platform in ("forge", "neoforge"):
        base = ROOT / f"source-platforms/{platform}/src/main/java/buildcraft/transport"
        api_rel = base / "internal/pipe/PipeApi.java"
        api = api_rel.read_text(encoding="utf-8")
        for token in (
            "fluidTransferData.get(def)", "def.getApiType().fluidProfile()",
            "powerTransferData.get(def)", "def.getApiType().mjProfile()",
            "forgeEnergyTransferData.get(def)", "def.getApiType().externalEnergyProfile()",
        ):
            if token not in api:
                fail(f"{api_rel.relative_to(ROOT)}: config/API2 fallback wiring lost {token!r}")

        flow_expectations = {
            "pipe/flow/PipeFlowFluids.java": "PipeApi.getFluidTransferInfo(pipe.getDefinition())",
            "pipe/flow/PipeFlowPower.java": "PipeApi.getPowerTransferInfo(pipe.getDefinition())",
            "pipe/flow/PipeFlowForgeEnergy.java": "PipeApi.getForgeEnergyTransferInfo(pipe.getDefinition())",
        }
        forbidden = {
            "pipe/flow/PipeFlowFluids.java": "getApiType().fluidProfile()",
            "pipe/flow/PipeFlowPower.java": "getApiType().mjProfile()",
            "pipe/flow/PipeFlowForgeEnergy.java": "getApiType().externalEnergyProfile()",
        }
        for rel, token in flow_expectations.items():
            path = base / rel
            text = path.read_text(encoding="utf-8")
            if token not in text:
                fail(f"{path.relative_to(ROOT)}: runtime flow bypasses configured PipeApi transfer data")
            if forbidden[rel] in text:
                fail(f"{path.relative_to(ROOT)}: runtime flow still snapshots API2 profile instead of config override")



def validate_randomium_facade_blacklist(props: dict[str, str]) -> None:
    """Keep facade variants out of Randomium's creative-tab loot scan.

    Randomium 1.19/1.20 uses randomium:blacklist under the legacy item-tag
    directory, while its 1.21 line renamed the tag to randomium:randomium_blacklist
    and Minecraft 1.21 uses the singular item tag directory. The facade item emits
    many creative-tab variants, so losing this compatibility tag can turn
    Randomium's per-item linear de-duplication into a pathological startup cost.
    """
    expected = {
        "1.19.2-forge": "data/randomium/tags/items/blacklist.json",
        "1.20.1-forge": "data/randomium/tags/items/blacklist.json",
        "1.21.1-neoforge": "data/randomium/tags/item/randomium_blacklist.json",
    }
    facade_id = "buildcraftsilicon:plug/facade"
    for target, rel in expected.items():
        resources = resource_map(target, props)
        path = resources.get(rel)
        if path is None:
            fail(f"{target}: missing Randomium facade blacklist tag {rel}")
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            fail(f"{target}: invalid Randomium facade blacklist JSON {rel}: {exc}")
        if data.get("replace") is not False:
            fail(f"{target}: Randomium facade blacklist must merge with Randomium defaults")
        values = data.get("values")
        if not isinstance(values, list) or facade_id not in values:
            fail(f"{target}: Randomium facade blacklist does not contain {facade_id}")


def validate_item_pipe_gametest_isolation() -> None:
    """Item-pipe conservation tests must only count their own marked cargo.

    Forge 1.19.2 runs all GameTests in a shared world and can expose unrelated
    ItemEntity instances inside a broad manually constructed AABB. A conservation
    test must therefore track the exact stack it injected, while still failing if
    that marked stack is actually duplicated/ejected by the pipe runtime.
    """
    for platform in ("forge", "neoforge"):
        path = ROOT / f"source-platforms/{platform}/src/gametest/java/buildcraft/gametest/BuildCraftPipeTransportGameTests.java"
        text = path.read_text(encoding="utf-8")
        for forbidden in ("countDroppedItems(", "new BlockPos(7, 3, 7)"):
            if forbidden in text:
                fail(f"{path.relative_to(ROOT)}: broad unscoped item-entity assertion returned: {forbidden!r}")
        for token in (
            'CARGO_MARKER_KEY = "buildcraft_test"',
            'TEST_BOUNDS_MAX = new BlockPos(6, 2, 6)',
            'countDroppedCargo(helper, "straight_line")',
            'countDroppedCargo(helper, "diamond_fallback")',
            'countDroppedCargo(helper, "accepted_count")',
            'countDroppedCargo(helper, "clay_preference")',
            '.filter(entity -> hasCargoMarker(entity.getItem(), marker))',
        ):
            if token not in text:
                fail(f"{path.relative_to(ROOT)}: cargo-isolated GameTest guard lost {token!r}")


def validate_build_metadata_and_source_hygiene(props: dict[str, str]) -> None:
    placeholders = ("$version", "${mcversion}", "${git_branch}", "${git_commit_hash}", "${git_commit_msg}", "${git_commit_author}")
    source_roots = (ROOT / "source-shared", ROOT / "source-families", ROOT / "source-platforms", ROOT / "source-family-platforms", ROOT / "version-src")
    for base in source_roots:
        for path in base.rglob("*.java"):
            if not path.is_file():
                continue
            text = path.read_text(encoding="utf-8")
            for token in placeholders:
                if token in text:
                    fail(f"unresolved Java build metadata placeholder {token!r}: {path.relative_to(ROOT)}")
        for path in base.rglob("*.jsonx"):
            if path.is_file() and "src/main/resources" in path.as_posix():
                fail(f"production resource tree contains ignored migration .jsonx: {path.relative_to(ROOT)}")

    for rel in ("build-logic/loaders/forge-target.gradle", "build-logic/loaders/neoforge-target.gradle"):
        text = (ROOT / rel).read_text(encoding="utf-8")
        for token in (
            "generateBuildCraftTarget",
            "BuildCraftTarget.java",
            "MOD_VERSION",
            "GIT_BRANCH",
            "GIT_COMMIT_HASH",
            "GIT_COMMIT_MESSAGE",
            "GIT_COMMIT_AUTHOR",
            "sourceSets.main.java.srcDir(generatedTargetSources)",
            "dependsOn(generateBuildCraftTarget)",
        ):
            if token not in text:
                fail(f"{rel}: generated Java build metadata wiring lost {token!r}")

    for target in target_ids(props):
        bclib = effective_java(target, "buildcraft/lib/BCLib.java", props)
        for token in (
            "BuildCraftTarget.MOD_VERSION",
            "BuildCraftTarget.MINECRAFT_VERSION",
            "BuildCraftTarget.GIT_BRANCH",
            "BuildCraftTarget.GIT_COMMIT_HASH",
            "BuildCraftTarget.GIT_COMMIT_MESSAGE",
            "BuildCraftTarget.GIT_COMMIT_AUTHOR",
        ):
            if token not in bclib:
                fail(f"{target}: BCLib build metadata lost {token!r}")
        if target.endswith("-neoforge"):
            for token in (
                "!isProductionEnvironment() || Boolean.getBoolean(\"buildcraft.dev\")",
                "net.neoforged.fml.loading.FMLLoader",
                "getCurrent",
                "isProduction",
                "net.neoforged.fml.loading.FMLEnvironment",
                "getField(\"production\")",
            ):
                if token not in bclib:
                    fail(f"{target}: BCLib DEV mode lost {token!r}")
            if "1.21.1 provides FMLLoader but not getCurrent" not in bclib:
                fail(f"{target}: BCLib must fall back when the current-environment accessor is unavailable")
        elif "!FMLEnvironment.production || Boolean.getBoolean(\"buildcraft.dev\")" not in bclib:
            fail(f"{target}: BCLib DEV mode lost Forge production detection")
        if "!false" in bclib:
            fail(f"{target}: BCLib DEV mode was corrupted by compatibility materialization")
        if "VERSION.startsWith" in bclib:
            fail(f"{target}: DEV mode still depends on a version placeholder")


def validate_facade_swap_recipe(props: dict[str, str]) -> None:
    expected = {
        "1.19.2-forge": "data/buildcraftsilicon/recipes/special/facade_swap.json",
        "1.20.1-forge": "data/buildcraftsilicon/recipes/special/facade_swap.json",
        "1.21.1-neoforge": "data/buildcraftsilicon/recipe/special/facade_swap.json",
    }
    for target, rel in expected.items():
        resources = resource_map(target, props)
        path = resources.get(rel)
        if path is None:
            fail(f"{target}: production facade swap recipe is missing: {rel}")
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            fail(f"{target}: invalid facade swap recipe {rel}: {exc}")
        if data != {"type": "buildcraftsilicon:facade_swap"}:
            fail(f"{target}: facade swap recipe must point at buildcraftsilicon:facade_swap, got {data}")

    legacy_recipes = effective_java("1.19.2-forge", "buildcraft/silicon/BCSiliconRecipes.java", props)
    if 'SERIALIZERS.register("facade_swap"' not in legacy_recipes or 'facade_swap_recipe_' in legacy_recipes:
        fail("1.19.2-forge: facade swap serializer id is not normalized to buildcraftsilicon:facade_swap")


def validate_snapshot_renderer_and_client_isolation(props: dict[str, str]) -> None:
    snapshot_rel = "buildcraft/builders/snapshot/ClientSnapshots.java"
    for target in target_ids(props):
        text = effective_java(target, snapshot_rel, props)
        for token in (
            "3D blueprint/template previews are intentionally disabled",
            "Intentionally disabled on all maintained Minecraft versions.",
            "public void renderSnapshot",
        ):
            if token not in text:
                fail(f"{target}: snapshot preview is not explicitly disabled; missing {token!r}")
        for token in ("RenderSystem.", "FakeWorld", "renderBlocks(", "renderBlockEntities(", "renderEntities("):
            if token in text:
                fail(f"{target}: disabled snapshot preview still contains live renderer token {token!r}")

    bccore = effective_java("1.21.1-neoforge", "buildcraft/core/BCCore.java", props)
    for forbidden in (
        "net.minecraft.client",
        "buildcraft.core.client",
        "net.neoforged.neoforge.client",
        "RenderTickListener",
        "Dist.CLIENT",
    ):
        if forbidden in bccore:
            fail(f"1.21.1-neoforge: common BCCore entrypoint still references client-only symbol {forbidden!r}")
    client_text = effective_java("1.21.1-neoforge", "buildcraft/core/client/BCCoreClientModEvents.java", props)
    for token in ("value = Dist.CLIENT", "RegisterMenuScreensEvent", "ModifyBakingResult", "RenderTickListener::renderOverlay"):
        if token not in client_text:
            fail(f"1.21.1-neoforge: client-only BCCore bootstrap lost {token!r}")



def validate_compat_runtime_dependencies(props: dict[str, str]) -> None:
    """Guard compatibility smoke dependencies against known-broken upstream releases."""
    target = "1.19.2-forge"
    ic2 = props.get(f"target.{target}.deps.ic2", "").strip()
    carbon = props.get(f"target.{target}.deps.carbon_config", "").strip()
    if not ic2:
        fail(f"{target}: IC2 compatibility is enabled without a runtime dependency")
    if not carbon:
        fail(f"{target}: IC2 compatibility is missing its Carbon Config runtime dependency")

    # Carbon Config 2.0.0 crashes when IC2 saves its config during dedicated-server
    # construction before ServerLifecycleHooks has a current MinecraftServer. Carbon Config 2.0.2
    # contains the required startup fix and is the verified compatibility version.
    known_bad = "maven.modrinth:1jDdpgcc:8U1HA7TK"
    required_hotfix = "maven.modrinth:1jDdpgcc:HMD2FUea"
    if carbon == known_bad:
        fail(f"{target}: Carbon Config 2.0.0 is known to crash IC2 dedicated-server startup")
    if carbon != required_hotfix:
        fail(
            f"{target}: IC2 compatibility smoke must use verified Carbon Config 2.0.2 "
            f"({required_hotfix}); found {carbon!r}."
        )

def validate_ci_wiring(props: dict[str, str]) -> None:
    ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

    runtime_start = ci.find("\n  build-test-server:\n")
    compatibility_start = ci.find("\n  Compatibility:\n", runtime_start)
    if runtime_start < 0 or compatibility_start < 0:
        fail("CI is missing the target runtime or compatibility job")
    runtime = ci[runtime_start:compatibility_start]
    validate_start = ci.find("\n  validate:\n")
    if validate_start < 0:
        fail("CI is missing the global validation job")
    validate = ci[validate_start:runtime_start]

    for token in (
        "python scripts/validate-fe-compat.py",
        "python scripts/validate-cross-target-integrity.py",
        "python scripts/validate-api2-runtime-completeness.py",
        "python scripts/validate-behavior-parity.py",
        "python scripts/validate-12111-parity.py",
    ):
        if token not in validate:
            fail(f"global validation CI lost required cross-target coverage: {token}")

    if "python scripts/validate-1.20.1-target.py" in validate:
        fail("1.20.1-only validation must run in the 1.20.1 target job, not block every target")

    matrix_entries = re.findall(
        r"- target:\s*([^\s]+)\s*\n\s+generation:\s*([^\s]+)\s*\n\s+java:\s*['\"]?([^'\"\s]+)['\"]?",
        runtime,
    )
    if len(matrix_entries) != len({target for target, _generation, _java in matrix_entries}):
        fail("target runtime CI matrix contains duplicate target entries")
    actual_matrix = {target: (generation, java) for target, generation, java in matrix_entries}
    expected_matrix = {
        target: (
            props[f"target.{target}.build.generation"],
            props[f"target.{target}.java.version"],
        )
        for target in target_ids(props)
    }
    if actual_matrix != expected_matrix:
        fail(f"target runtime CI matrix drifted: expected {expected_matrix}, found {actual_matrix}")

    for token in (
        "name: Build, test and smoke ${{ matrix.target }}",
        "max-parallel: 4",
        "BUILD_GENERATION: ${{ matrix.generation }}",
        "STONECUTTER_TARGET: ${{ matrix.target }}",
        "java-version: ${{ matrix.java }}",
        "if: matrix.target == '1.20.1-forge'",
        "python scripts/validate-1.20.1-target.py --source-root version-src/1.20.1-forge",
        '":${STONECUTTER_TARGET}:buildAndCollect"',
        '":${STONECUTTER_TARGET}:runGameTestServer"',
        "SERVER_RUNTIME_PROFILE: base",
        "scripts/ci-server-smoke.sh",
        "CLIENT_RUNTIME_PROFILE: jei",
        "scripts/ci-client-smoke.sh",
        "name: buildcraft-${{ matrix.target }}-${{ github.run_number }}-${{ github.run_attempt }}",
        "build/*/*.jar",
    ):
        if token not in runtime:
            fail(f"target runtime CI lost required isolated coverage: {token}")

    for forbidden in (
        "Build, test and smoke ${{ matrix.generation }} targets",
        "mapfile -t targets",
        '"${target}:runGameTestServer"',
        '":${target}:runGameTestServer"',
        "gametest_status=0",
        "client_status=0",
        "versions/*/build/libs/**",
        "build/libs/${{ matrix.generation }}/**",
        "run/${{ matrix.generation }}/*/logs/**",
    ):
        if forbidden in runtime:
            fail(f"target runtime CI still contains family-wide execution: {forbidden}")

    for entry in (
        "- target: 1.19.2-forge\n            profile: forestry",
        "- target: 1.19.2-forge\n            profile: ic2",
        "- target: 1.20.1-forge\n            profile: forestry",
    ):
        if entry not in ci:
            fail(f"compatibility CI matrix lost required target/profile pair: {entry.replace(chr(10), ' / ')}")
    if "continue-on-error: true" in ci:
        fail("compatibility smoke must be blocking; continue-on-error is still enabled")

    client_script = ROOT / "scripts/ci-client-smoke.sh"
    if not client_script.is_file():
        fail("missing scripts/ci-client-smoke.sh")
    client = client_script.read_text(encoding="utf-8")
    for token in (
        ":${target}:runClient",
        "xvfb-run",
        "blocks\\.png-atlas",
        "Missing model for variant",
        'local var="JAVA_HOME_${major}_X64"',
        'local home="${!var:-}"',
        "Invalid path in pack: buildcraft",
        "resource_error_seen=1",
        "collecting the rest of the reload before failing",
    ):
        if token not in client:
            fail(f"client smoke script lost {token!r}")
    if 'local major="$1" var=' in client:
        fail("client smoke Java-home lookup must not use same-command local assignments with indirect expansion")

    server_script = ROOT / "scripts/ci-server-smoke.sh"
    if not server_script.is_file():
        fail("missing scripts/ci-server-smoke.sh")
    server = server_script.read_text(encoding="utf-8")
    for token in (
        "expected_buildcraft_version",
        'jar_dir="${repo_root}/build/${minecraft_version}"',
        'production_jar="${jar_dir}/${archive_name}-${expected_buildcraft_version}.jar"',
        "Starting BuildCraft ${expected_buildcraft_version}",
        "unresolved BuildCraft Java build metadata",
    ):
        if token not in server:
            fail(f"server smoke script lost runtime build-metadata/output guard {token!r}")

    common_target = (ROOT / "build-logic/common-target.gradle").read_text(encoding="utf-8")
    for token in (
        'new File(repositoryRoot, "build/${minecraftVersion}")',
        "tasks.withType(org.gradle.api.tasks.bundling.Jar).configureEach",
        "destinationDirectory.set(centralJarOutputDir)",
        "cleanCentralJarOutput",
    ):
        if token not in common_target:
            fail(f"shared target build lost centralized JAR output rule {token!r}")

    for loader_script in (
        ROOT / "build-logic/loaders/forge-target.gradle",
        ROOT / "build-logic/loaders/neoforge-target.gradle",
    ):
        loader = loader_script.read_text(encoding="utf-8")
        if 'new File(repositoryRoot, "build/libs/${buildGeneration}/${modVersion}")' in loader:
            fail(f"{loader_script.name} still writes release jars to the old generation/mod-version folder")
        if "tasks.register('buildAndCollect', Copy)" in loader:
            fail(f"{loader_script.name} still copies JARs after build instead of using the shared output directory")


def main() -> None:
    props = load_properties()
    validate_atlases_and_case(props)
    validate_stack_parity(props)
    validate_machine_fluid_drop_ownership(props)
    validate_metadata(props)
    validate_datagen_isolation()
    validate_language_policy(props)
    validate_guide_resources()
    validate_quartz_sprite_paths()
    validate_promoted_cross_generation_files()
    validate_hotspots(props)
    validate_pipe_recipe_schema(props)
    validate_pipe_transfer_wiring()
    validate_randomium_facade_blacklist(props)
    validate_item_pipe_gametest_isolation()
    validate_build_metadata_and_source_hygiene(props)
    validate_facade_swap_recipe(props)
    validate_snapshot_renderer_and_client_isolation(props)
    validate_compat_runtime_dependencies(props)
    validate_ci_wiring(props)
    print("Cross-target integrity OK:")
    print(" - exact-case atlas/model resources verified")
    print(" - clean/used item stack-size parity and single-owner machine fluid drops verified")
    print(" - datagen isolated from production resources")
    print(" - main resources are en_us-only; localization addon ownership guarded")
    print(" - custom pipe recipe schema and configured transfer wiring verified")
    print(" - Randomium facade blacklist compatibility guarded on maintained targets")
    print(" - item-pipe GameTests track only their own marked cargo")
    print(" - Java build metadata placeholders and production .jsonx files forbidden")
    print(" - facade swap production recipe and snapshot/client isolation guarded")
    print(" - IC2 compatibility uses the dedicated-server-safe Carbon Config hotfix")
    print(" - target-isolated build, GameTest, client/server smoke and blocking compatibility CI coverage guarded")


if __name__ == "__main__":
    main()
