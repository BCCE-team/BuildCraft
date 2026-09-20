#!/usr/bin/env python3
"""Deterministic resource transforms and generators for all BCCE targets.

Maintained resources stay semantic source assets. Mechanical compatibility
formats are produced here at materialization time so version overlays remain
small and boring.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import shutil

from source_preprocessor import version_tuple

ROOT = Path(__file__).resolve().parents[2]
_version_tuple = version_tuple

def _rewrite_legacy_ingredient_json(value):
    if isinstance(value, dict):
        if set(value.keys()) == {"item"} and isinstance(value.get("item"), str):
            return value["item"]
        if set(value.keys()) == {"tag"} and isinstance(value.get("tag"), str):
            return "#" + value["tag"]
        rewritten = {key: _rewrite_legacy_ingredient_json(child) for key, child in value.items()}
        ingredient_type = rewritten.get("type")
        if isinstance(ingredient_type, str) and ":" in ingredient_type and "neoforge:ingredient_type" not in rewritten:
            rewritten = dict(rewritten)
            rewritten["neoforge:ingredient_type"] = rewritten.pop("type")
        return rewritten
    if isinstance(value, list):
        return [_rewrite_legacy_ingredient_json(child) for child in value]
    return value


def _rewrite_121111_recipe_json(text: str, normalized: str) -> str:
    if "/data/" not in normalized or "/recipe/" not in normalized or not normalized.endswith(".json"):
        return text
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return text
    if not isinstance(data, dict):
        return text

    recipe_type = data.get("type")
    changed = False

    if recipe_type == "minecraft:crafting_shaped" and isinstance(data.get("key"), dict):
        data["key"] = {
            key: _rewrite_legacy_ingredient_json(value)
            for key, value in data["key"].items()
        }
        changed = True
    elif recipe_type == "minecraft:crafting_shapeless" and "ingredients" in data:
        data["ingredients"] = _rewrite_legacy_ingredient_json(data["ingredients"])
        changed = True
    elif recipe_type == "buildcraftsilicon:assembly" and "ingredients" in data:
        data["ingredients"] = _rewrite_legacy_ingredient_json(data["ingredients"])
        changed = True
    elif recipe_type == "buildcrafttransport:pipe":
        for key in ("left", "middle", "right", "from", "additional"):
            if key in data:
                data[key] = _rewrite_legacy_ingredient_json(data[key])
                changed = True

    if not changed:
        return text
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


def _generated_item_model(texture: str) -> str:
    return json.dumps({
        "parent": "minecraft:item/generated",
        "textures": {
            "layer0": texture,
            "particle": texture,
        },
    }, indent=2, ensure_ascii=False) + "\n"


def _minecraft_model_client_item(model: str) -> str:
    return json.dumps({
        "model": {
            "type": "minecraft:model",
            "model": model,
        },
    }, indent=2, ensure_ascii=False) + "\n"


def _dynamic_fluid_bucket_client_item_121111(fluid: str) -> str:
    # Minecraft 1.21.11 moved dynamic item-model selection into assets/<ns>/items.
    # NeoForge 21.11.x requires an explicit fallback `fluid` in the
    # DynamicFluidContainerModel codec (fieldOf("fluid")); omitting it makes
    # the entire client item fail to decode and render as missingno. BucketItem
    # still exposes its contained fluid at runtime, but the static fluid id is
    # mandatory and is also the correct fallback for an empty capability view.
    return json.dumps({
        "model": {
            "type": "neoforge:fluid_container",
            "textures": {
                "particle": "minecraft:item/bucket",
                "base": "minecraft:item/bucket",
                "fluid": "neoforge:item/mask/bucket_fluid",
                "cover": "neoforge:item/mask/bucket_fluid_cover",
            },
            "fluid": fluid,
            "flip_gas": True,
            "cover_is_mask": True,
            "apply_fluid_luminosity": False,
        },
    }, indent=2, ensure_ascii=False) + "\n"


def _buildcraftenergy_bucket_fluid_id_121111(item_path: str) -> str | None:
    parts = item_path.split("/")
    if len(parts) != 2:
        return None
    family, bucket_name = parts
    if not bucket_name.endswith("_bucket"):
        return None

    heat = bucket_name.removesuffix("_bucket")
    if heat == "cool":
        fluid_path = family
    elif heat == "hot":
        fluid_path = f"{family}_heat_1"
    elif heat == "searing":
        fluid_path = f"{family}_heat_2"
    else:
        return None
    return f"buildcraftenergy:{fluid_path}"


def _buildcraftenergy_bucket_item_path_121111(normalized: str, marker: str) -> str | None:
    if marker not in normalized or not normalized.endswith(".json"):
        return None
    item_path = normalized.split(marker, 1)[1].removesuffix(".json")
    return item_path if "/" in item_path and item_path.endswith("_bucket") else None


PIPE_ITEM_TEXTURE_ALIASES_121111 = {
    # Match PipeDefinitionBuilder.itemTex(...) / the inherited texture suffixes
    # used by BCTransportPipes.  The old dynamic ModelPipeItem selected these
    # sprites at runtime; 1.21.11 no longer installs that legacy baked model.
    "wood_item": "wood_item_clear",
    "wood_fluid": "wood_fluid_clear",
    "wood_power": "wood_power_clear",
    "wood_fe": "wood_fe_clear",
    "iron_item": "iron_item_clear",
    "iron_fluid": "iron_fluid_clear",
    "diamond_item": "diamond_item_itemstack",
    "diamond_fluid": "diamond_fluid_itemstack",
    "iron_power": "iron_power_m128",
    "diamond_power": "diamond_power_m128",
    "iron_fe": "iron_fe_m128",
    "diamond_fe": "diamond_fe_m128",
    "diamond_wood_item": "diamond_wood_item_clear",
    "diamond_wood_fluid": "diamond_wood_fluid_clear",
    "diamond_wood_power": "diamond_wood_power_clear",
    "diamond_wood_fe": "diamond_wood_fe_clear",
    "lapis_item": "lapis_item_base",
    "daizuli_item": "daizuli_item_filled",
    "emzuli_item": "emzuli_item_clear",
}


def _pipe_item_texture_121111(item: str) -> str:
    return PIPE_ITEM_TEXTURE_ALIASES_121111.get(item, item)


def _pipe_3d_item_model_121111(texture: str) -> str:
    """Recreate the legacy ModelPipeItem body with vanilla 1.21.11 model JSON.

    ModelPipeItem renders an 8x16x8 cuboid (x/z 4..12) using 4..12 cap UVs
    and 4..12 x 0..16 side UVs.  Keeping the texture in the block atlas is
    intentional: the same sprite is used by the in-world pipe renderer and it
    avoids modifying/replacing Minecraft's item atlas.
    """
    side_uv = [4, 0, 12, 16]
    cap_uv = [4, 4, 12, 12]
    return json.dumps({
        "parent": "minecraft:block/block",
        "ambientocclusion": False,
        "gui_light": "front",
        "textures": {
            "pipe": texture,
            "particle": texture,
        },
        "elements": [{
            "from": [4, 0, 4],
            "to": [12, 16, 12],
            "shade": False,
            "faces": {
                "down": {"uv": cap_uv, "texture": "#pipe"},
                "up": {"uv": cap_uv, "texture": "#pipe"},
                "north": {"uv": side_uv, "texture": "#pipe"},
                "south": {"uv": side_uv, "texture": "#pipe"},
                "west": {"uv": side_uv, "texture": "#pipe"},
                "east": {"uv": side_uv, "texture": "#pipe"},
            },
        }],
    }, indent=2, ensure_ascii=False) + "\n"


def _is_121111_buildcraftenergy_bucket_client_item(normalized: str) -> bool:
    return _buildcraftenergy_bucket_item_path_121111(normalized, "/assets/buildcraftenergy/items/") is not None


def _is_121111_buildcraftenergy_bucket_item_model(normalized: str) -> bool:
    return _buildcraftenergy_bucket_item_path_121111(normalized, "/assets/buildcraftenergy/models/item/") is not None


def _buildcraftenergy_bucket_client_item_121111(normalized: str) -> str:
    item_path = _buildcraftenergy_bucket_item_path_121111(normalized, "/assets/buildcraftenergy/items/")
    if item_path is None:
        return normalized
    fluid = _buildcraftenergy_bucket_fluid_id_121111(item_path)
    if fluid is None:
        return normalized
    return _dynamic_fluid_bucket_client_item_121111(fluid)


def _buildcraftenergy_bucket_generated_model_121111(normalized: str) -> str:
    # The actual 1.21.11 bucket renderer now lives in the client-item definition
    # above. Keep a harmless modern vanilla-bucket fallback for any legacy code
    # that still resolves the old baked-model location directly; critically, do
    # NOT point this back at BuildCraft's archived pre-1.21 bucket PNGs.
    item_path = _buildcraftenergy_bucket_item_path_121111(normalized, "/assets/buildcraftenergy/models/item/")
    if item_path is None:
        return ""
    return _generated_item_model("minecraft:item/bucket")


def _fallback_121111_item_model(normalized: str, text: str) -> str | None:
    """Return a simple generated item model for legacy dynamic item models.

    Keep only the compatibility fallback for legacy item-pipe models that still
    have a bare block/block parent. Pluggables are deliberately excluded here:
    static pluggables have real 3D JSON models and dynamic silicon pluggables
    are supplied by the native 1.21.11 ItemModel bridge during model baking.
    """
    if "/assets/buildcrafttransport/models/item/" in normalized:
        item = normalized.rsplit("/", 1)[-1].removesuffix(".json")
        if "/models/item/wire/" in normalized or "/models/item/pipewire/" in normalized:
            return None
        if item == "waterproof":
            return None
        if item == "wire":
            return _generated_item_model("buildcrafttransport:items/wire/white")
        if item == "filtered_buffer":
            return None
        stripped = text.strip().replace(" ", "")
        if stripped == '{"parent":"block/block"}' or '"parent": "block/block"' in text:
            texture = _pipe_item_texture_121111(item)
            return _pipe_3d_item_model_121111(f"buildcrafttransport:pipes/{texture}")

    return None


def _apply_121111_resource_compat(text: str, *, minecraft: str, relative: str) -> str:
    """Apply 1.21.11 resource-pack/model compatibility rewrites.

    Minecraft 1.21.11 validates packs above format 64 with the newer
    min_format/max_format metadata pair. Keep the generated pack in the
    1.21.11-compatible window explicitly so the create-world data reload
    accepts the bundled mod resources.
    The recipe ingredient JSON shape also changed from legacy {item/tag}
    objects to holder strings, and NeoForge 21.11 no longer exposes the old
    fluid-container JSON model loaders that the 1.21.1 resource layer used.
    Keep this as a materialization-only bridge so the maintained modern
    resources can still serve 1.21.1.
    """
    if _version_tuple(minecraft) < _version_tuple("1.21.11"):
        return text

    normalized = relative.replace("\\", "/")

    if normalized.endswith("src/main/resources/pack.mcmeta") or normalized.endswith("pack.mcmeta"):
        text = re.sub(r'\n\s*"min_format"\s*:\s*(?:\[[^\]]+\]|\$\{pack_format\}|\d+(?:\.\d+)?)\s*,?', "", text)
        text = re.sub(r'\n\s*"max_format"\s*:\s*(?:\[[^\]]+\]|\$\{pack_format\}|\d+(?:\.\d+)?)\s*,?', "", text)
        text = re.sub(r'\n\s*"supported_formats"\s*:\s*(?:\[[^\]]+\]|\{[^}]+\})\s*,?', "", text)
        text = text.replace(
            '"pack_format": ${pack_format},',
            '"pack_format": ${pack_format},\n    "min_format": ${pack_format},\n    "max_format": ${pack_format},',
        )
        return text

    if normalized.endswith(".json") and "/recipe/" in normalized and "/data/" in normalized:
        text = _rewrite_121111_recipe_json(text, normalized)

    if _is_121111_buildcraftenergy_bucket_client_item(normalized):
        return _buildcraftenergy_bucket_client_item_121111(normalized)

    if _is_121111_buildcraftenergy_bucket_item_model(normalized):
        return _buildcraftenergy_bucket_generated_model_121111(normalized)

    if not normalized.endswith(".json") or "/models/item/" not in normalized:
        return text

    if '"loader": "buildcraftcore:fragile_fluid_container"' in text:
        return _generated_item_model("buildcraftcore:items/fragile_fluid_shard_base")

    fallback = _fallback_121111_item_model(normalized, text)
    if fallback is not None:
        return fallback

    return text




def apply_resource_transforms(text: str, *, minecraft: str, relative: str) -> str:
    text = _apply_121111_resource_compat(text, minecraft=minecraft, relative=relative)
    normalized = relative.replace("\\", "/")
    text = _downport_legacy_oil_placement(text, minecraft=minecraft, normalized=normalized)
    text = _augment_legacy_120_block_atlas(text, minecraft=minecraft, normalized=normalized)
    return text


def generate_modern_item_definitions(destination_root: Path, *, minecraft: str) -> int:
    """Generate mechanical modern client-item definitions from item models.

    For models without legacy predicate overrides, each definition is fully
    deterministic: it either points at the same item model or uses NeoForge's
    fluid-container model for BuildCraft Energy buckets. Predicate-driven items are maintained as canonical modern resources in the
    modern family; only one-to-one definitions are generated here.
    """
    if _version_tuple(minecraft) < _version_tuple("1.21.11"):
        return 0

    assets = destination_root / "src" / "main" / "resources" / "assets"
    if not assets.is_dir():
        return 0

    generated = 0
    for namespace_root in sorted(path for path in assets.iterdir() if path.is_dir()):
        namespace = namespace_root.name
        if not namespace.startswith("buildcraft"):
            continue
        model_root = namespace_root / "models" / "item"
        if not model_root.is_dir():
            continue
        for model_path in sorted(model_root.rglob("*.json")):
            try:
                model_json = json.loads(model_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            # Predicate-driven definitions need range_dispatch; this generator handles
            # only mechanical one-to-one item-model definitions.
            if isinstance(model_json, dict) and model_json.get("overrides"):
                continue

            item_rel = model_path.relative_to(model_root)
            item_path = item_rel.with_suffix("").as_posix()
            output = namespace_root / "items" / item_rel
            if output.exists():
                continue

            content: str
            fluid = (
                _buildcraftenergy_bucket_fluid_id_121111(item_path)
                if namespace == "buildcraftenergy"
                else None
            )
            if fluid is not None:
                content = _dynamic_fluid_bucket_client_item_121111(fluid)
            else:
                content = _minecraft_model_client_item(f"{namespace}:item/{item_path}")

            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(content, encoding="utf-8", newline="")
            generated += 1
    return generated


LEGACY_ENERGY_FLUIDS = (
    "fuel_dense",
    "fuel_gaseous",
    "fuel_light",
    "fuel_mixed_heavy",
    "fuel_mixed_light",
    "oil",
    "oil_dense",
    "oil_distilled",
    "oil_heavy",
    "oil_residue",
)


def _optional_id(value: str) -> dict[str, object]:
    return {"id": value, "required": False}


def _legacy_energy_tag_values(base: str, heat: int, *, include_ic2: bool) -> list[object]:
    suffix = "" if heat == 0 else f"_heat_{heat}"
    fluid = f"{base}{suffix}"
    values: list[object] = [
        f"buildcraftenergy:{fluid}",
    ]
    if include_ic2:
        if heat == 0:
            aliases = (
                base,
                f"{base}_cool",
            )
        elif heat == 1:
            aliases = (
                f"{base}_heat_1",
                f"hot_{base}",
                f"{base}_hot",
            )
        else:
            aliases = (
                f"{base}_heat_2",
                f"searing_{base}",
                f"{base}_searing",
            )
        values.extend(_optional_id(f"ic2:{alias}") for alias in aliases)
    values.append(f"buildcraftenergy:{fluid}_flowing")
    if include_ic2:
        if heat == 0:
            flow_aliases = (
                f"{base}_flowing",
                f"flowing_{base}",
                f"{base}_cool_flowing",
                f"flowing_{base}_cool",
            )
        elif heat == 1:
            flow_aliases = (
                f"{base}_heat_1_flowing",
                f"flowing_{base}_heat_1",
                f"hot_{base}_flowing",
                f"flowing_hot_{base}",
                f"{base}_hot_flowing",
                f"flowing_{base}_hot",
            )
        else:
            flow_aliases = (
                f"{base}_heat_2_flowing",
                f"flowing_{base}_heat_2",
                f"searing_{base}_flowing",
                f"flowing_searing_{base}",
                f"{base}_searing_flowing",
                f"flowing_{base}_searing",
            )
        values.extend(_optional_id(f"ic2:{alias}") for alias in flow_aliases)
    return values


def _write_generated_json(path: Path, data: object) -> None:
    if path.exists():
        raise ValueError(f"generated resource would overwrite maintained source: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="")


def _write_generated_text(path: Path, text: str) -> None:
    if path.exists():
        raise ValueError(f"generated resource would overwrite maintained source: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="")


def generate_legacy_forge_energy_tags(
    destination_root: Path, *, minecraft: str, family: str, platform: str
) -> int:
    if family != "legacy" or platform != "forge":
        return 0
    if _version_tuple(minecraft) not in {_version_tuple("1.19.2"), _version_tuple("1.20.1")}:
        return 0

    include_ic2 = _version_tuple(minecraft) < _version_tuple("1.20")
    root = destination_root / "src/main/resources/data/forge/tags/fluids"
    generated = 0
    for base in LEGACY_ENERGY_FLUIDS:
        for heat, prefix in ((0, ""), (1, "hot_"), (2, "searing_")):
            _write_generated_json(
                root / f"{prefix}{base}.json",
                {
                    "replace": False,
                    "values": _legacy_energy_tag_values(base, heat, include_ic2=include_ic2),
                },
            )
            generated += 1
    return generated


def _ic2_cell_model(fluid: str) -> dict[str, object]:
    return {
        "parent": "forge:item/default",
        "loader": "forge:fluid_container",
        "fluid": f"buildcraftenergy:{fluid}",
        "flip_gas": False,
        "cover_is_mask": False,
        "apply_fluid_luminosity": True,
        "apply_tint": False,
        "textures": {
            "particle": "ic2:item/cells/empty",
            "base": "ic2:item/cells/empty",
            "fluid": "buildcraftenergy:items/ic2_cell_fluid",
        },
    }


def generate_legacy_ic2_cell_models(
    destination_root: Path, *, minecraft: str, family: str, platform: str
) -> int:
    if family != "legacy" or platform != "forge" or _version_tuple(minecraft) >= _version_tuple("1.20"):
        return 0
    root = destination_root / "src/main/resources/assets/buildcraftenergy/models/item/ic2_cell"
    generated = 0
    for base in LEGACY_ENERGY_FLUIDS:
        for heat in (0, 1, 2):
            fluid = base if heat == 0 else f"{base}_heat_{heat}"
            _write_generated_json(root / f"{fluid}.json", _ic2_cell_model(fluid))
            generated += 1
    return generated


def _downport_legacy_oil_placement(text: str, *, minecraft: str, normalized: str) -> str:
    if not normalized.endswith("/data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json"):
        return text
    if _version_tuple(minecraft) >= _version_tuple("1.20"):
        return text
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return text
    try:
        settings = data["feature"]["config"]["oilStructureSetting"]
        for key in (
            "enableOilSpouts",
            "smallSpoutMinHeight",
            "smallSpoutMaxHeight",
            "largeSpoutMinHeight",
            "largeSpoutMaxHeight",
        ):
            settings.pop(key, None)
        for placement in data.get("placement", []):
            if placement.get("type") == "minecraft:count":
                placement["type"] = "count"
    except (KeyError, TypeError, AttributeError):
        return text
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


PROPOLIS_ATLAS_RESOURCES = (
    "buildcraftcompat:pipes/propolis",
    "buildcraftcompat:pipes/propolis_down",
    "buildcraftcompat:pipes/propolis_up",
    "buildcraftcompat:pipes/propolis_north",
    "buildcraftcompat:pipes/propolis_south",
    "buildcraftcompat:pipes/propolis_west",
    "buildcraftcompat:pipes/propolis_east",
    "buildcraftcompat:pipes/propolis_itemstack",
)


def _augment_legacy_120_block_atlas(text: str, *, minecraft: str, normalized: str) -> str:
    if not normalized.endswith("/assets/minecraft/atlases/blocks.json"):
        return text
    if _version_tuple(minecraft) != _version_tuple("1.20.1"):
        return text
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return text
    sources = data.get("sources")
    if not isinstance(sources, list):
        return text
    existing = {
        source.get("resource")
        for source in sources
        if isinstance(source, dict) and isinstance(source.get("resource"), str)
    }
    for resource in PROPOLIS_ATLAS_RESOURCES:
        if resource not in existing:
            sources.append({"type": "minecraft:single", "resource": resource})
    return json.dumps(data, indent=2, ensure_ascii=False) + "\n"


CANONICAL_MODERN_RESOURCE_ROOT = ROOT / "source-families/modern/src/main/resources"
VERSIONED_RESOURCE_ROOT = ROOT / "resource-src"


def install_versioned_resource_sources(
    destination_root: Path, *, minecraft: str, family: str
) -> int:
    """Install hand-authored resource variants for a source family.

    Versioned resource sources stay outside normal Java/resource source roots so
    every maintained JSON remains valid JSON.  Eligible version directories are
    overlaid oldest-to-newest before mechanical generators run, allowing later
    variants to replace lower-family resources deliberately without target
    overlays or comment-based selectors inside JSON.
    """
    family_root = VERSIONED_RESOURCE_ROOT / family
    if not family_root.is_dir():
        return 0

    eligible: list[tuple[tuple[int, ...], Path]] = []
    current = _version_tuple(minecraft)
    for version_root in family_root.iterdir():
        if not version_root.is_dir():
            continue
        try:
            version = _version_tuple(version_root.name)
        except (TypeError, ValueError):
            continue
        if version <= current:
            eligible.append((version, version_root))

    installed = 0
    output_root = destination_root / "src/main/resources"
    for _, version_root in sorted(eligible, key=lambda entry: entry[0]):
        for source in sorted(path for path in version_root.rglob("*") if path.is_file()):
            relative = source.relative_to(version_root)
            output = output_root / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, output)
            installed += 1
    return installed


def generate_legacy_resources_from_modern_canonical(
    destination_root: Path, *, minecraft: str, family: str, platform: str
) -> int:
    """Downport selected newest-format resources into the legacy family.

    These resources are identical between 1.20.1 and modern, or have a small
    deterministic 1.19/1.20 compatibility transform. Keeping one authoritative
    copy avoids large target overlays without moving version conditions into
    the shared source layer.
    """
    if family != "legacy" or platform != "forge":
        return 0

    always = (
        "data/buildcraftenergy/worldgen/placed_feature/oil_placed_feature.json",
    )
    from_120 = (
        "assets/buildcraftcore/models/block/engine_trunk_light_sprite.json",
        "assets/buildcraftlib/models/block/engine_chamber_sprite.json",
        "assets/minecraft/atlases/blocks.json",
    )
    rels = list(always)
    if _version_tuple(minecraft) >= _version_tuple("1.20"):
        rels.extend(from_120)

    generated = 0
    for rel in rels:
        source = CANONICAL_MODERN_RESOURCE_ROOT / rel
        if not source.is_file():
            raise ValueError(f"missing canonical modern resource: {source}")
        text = source.read_text(encoding="utf-8")
        normalized = f"src/main/resources/{rel}"
        text = _downport_legacy_oil_placement(text, minecraft=minecraft, normalized=normalized)
        text = _augment_legacy_120_block_atlas(text, minecraft=minecraft, normalized=normalized)
        _write_generated_text(destination_root / "src/main/resources" / rel, text)
        generated += 1
    return generated


def generate_pipe_recipe_unlocks(destination_root: Path, *, minecraft: str) -> int:
    """Fill missing recipe-book rewards, without changing existing advancement criteria.

    The pipe serializer includes reversible upgrades and FE variants whose old
    data set had no unlock advancement. Generate those from their actual source
    ingredient; never duplicate a hand-authored reward or register a fake recipe.
    """
    if _version_tuple(minecraft) < _version_tuple("1.21.11"):
        return 0
    data = destination_root / "src/main/resources/data/buildcrafttransport"
    recipes = data / "recipe"
    advancements = data / "advancement"
    rewarded: set[str] = set()
    for path in sorted(advancements.rglob("*.json")):
        value = json.loads(path.read_text(encoding="utf-8"))
        rewarded.update(value.get("rewards", {}).get("recipes", []))
    generated = 0
    for path in sorted(recipes.rglob("*.json")):
        value = json.loads(path.read_text(encoding="utf-8"))
        if value.get("type") != "buildcrafttransport:pipe":
            continue
        relative = path.relative_to(recipes).with_suffix("").as_posix()
        recipe_id = "buildcrafttransport:" + relative
        if recipe_id in rewarded:
            continue
        source = value.get("from", value.get("left"))
        if isinstance(source, dict):
            source = source.get("item") or ("#" + source["tag"] if "tag" in source else None)
        if not isinstance(source, str) or not source:
            raise ValueError(f"Pipe recipe {recipe_id} needs an explicit unlock advancement")
        output = advancements / "recipe/buildcraft.pipes/generated" / (relative + ".json")
        _write_generated_json(output, {
            "parent": "minecraft:recipes/root",
            "criteria": {
                "has_source": {"trigger": "minecraft:inventory_changed",
                               "conditions": {"items": [{"items": source}]}},
                "has_the_recipe": {"trigger": "minecraft:recipe_unlocked",
                                   "conditions": {"recipe": recipe_id}},
            },
            "requirements": [["has_source", "has_the_recipe"]],
            "rewards": {"recipes": [recipe_id]},
        })
        generated += 1
    return generated


def generate_target_resources(
    destination_root: Path, *, minecraft: str, family: str, platform: str
) -> int:
    generated = 0
    # Hand-authored version variants must exist before mechanical generation so
    # special item definitions win over one-to-one generated definitions.
    generated += install_versioned_resource_sources(
        destination_root, minecraft=minecraft, family=family
    )
    generated += generate_modern_item_definitions(destination_root, minecraft=minecraft)
    generated += generate_pipe_recipe_unlocks(destination_root, minecraft=minecraft)
    generated += generate_legacy_forge_energy_tags(
        destination_root, minecraft=minecraft, family=family, platform=platform
    )
    generated += generate_legacy_ic2_cell_models(
        destination_root, minecraft=minecraft, family=family, platform=platform
    )
    generated += generate_legacy_resources_from_modern_canonical(
        destination_root, minecraft=minecraft, family=family, platform=platform
    )
    return generated
