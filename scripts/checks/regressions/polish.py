#!/usr/bin/env python3
import json
import struct
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path
errors = []


def read(rel):
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8")


def require(rel, *tokens):
    text = read(rel)
    for token in tokens:
        if token not in text:
            errors.append(f"{rel}: missing polish regression guard {token!r}")


def forbid(rel, *tokens):
    text = read(rel)
    for token in tokens:
        if token in text:
            errors.append(f"{rel}: forbidden polish regression token {token!r}")


# Water Gel is a random-tick plant-like block, but only the final solid GEL stage is harvestable.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/factory/block/BlockWaterGel.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/factory/block/BlockWaterGel.java",
    "source-platforms/neoforge/src/main/java/buildcraft/factory/block/BlockWaterGel.java",
):
    require(
        rel,
        "state.getValue(PROP_STAGE) != GelStage.GEL",
        "return ImmutableList.of();",
        "return ImmutableList.of(new ItemStack(BCFactoryItems.GEL.get()))",
    )
    forbid(rel, "state.getValue(PROP_STAGE).spreading ?")

# Preserve the delayed-fluid ring-buffer phase across unload/reload.
for platform in ("forge", "neoforge"):
    flow = f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java"
    require(
        flow,
        'nbt.putInt("currentTime", currentTime)',
        'Math.floorMod(nbt.getInt("currentTime"), incoming.length)',
    )
    test = f"source-platforms/{platform}/src/gametest/java/buildcraft/transport/pipe/flow/PipeFluidPowerGameTests.java"
    require(
        test,
        'centreNbt.contains("currentTime")',
        '"fluid delay phase changed after NBT round-trip"',
    )

# Path markers have their own possible-connection laser and must not fall back to generic marker visuals.
for family in ("legacy", "modern"):
    rel = f"source-families/{family}/src/main/java/buildcraft/core/marker/PathSubCache.java"
    require(rel, "BuildCraftLaserManager.MARKER_PATH_POSSIBLE")
    forbid(rel, "return null;//BuildCraftLaserManager.MARKER_PATH_POSSIBLE")

# 1.20.1 must honor the same facade-disable contract through generic Recipe#getResultItem as other targets.
require(
    "version-src/1.20.1-forge/src/main/java/buildcraft/silicon/recipe/FacadeAssemblyRecipes.java",
    "public ItemStack getResultItem(RegistryAccess registryAccess)",
    "if (!BCSiliconConfig.enableFacades)",
    "return ItemStack.EMPTY;",
)

# VariablePartLed uses a BuildCraft-owned atlas sprite, not a resource-pack-sensitive vanilla quartz texture.
for platform in ("forge", "neoforge"):
    rel = f"source-platforms/{platform}/src/main/java/buildcraft/lib/client/model/json/VariablePartLed.java"
    require(rel, '"buildcraftlib", "model/led_fallback"', "getSprite(FALLBACK_SPRITE)")
    forbid(rel, "QUARTZ_BLOCK", "quartz_block", "ForgeRegistries.BLOCKS", "BuiltInRegistries.BLOCK")

require(
    "version-src/1.19.2-forge/src/main/java/buildcraft/lib/BCLibEventDist.java",
    'event.addSprite(new ResourceLocation("buildcraftlib", "model/led_fallback"))',
)

for rel in (
    "source-families/modern/src/main/resources/assets/minecraft/atlases/blocks.json",
):
    text = read(rel)
    try:
        data = json.loads(text)
    except json.JSONDecodeError as exc:
        errors.append(f"{rel}: invalid atlas JSON: {exc}")
        continue
    matches = [entry for entry in data.get("sources", []) if entry.get("resource") == "buildcraftlib:model/led_fallback"]
    if len(matches) != 1:
        errors.append(f"{rel}: expected exactly one buildcraftlib:model/led_fallback atlas entry, found {len(matches)}")

png_rel = "source-shared/src/main/resources/assets/buildcraftlib/textures/model/led_fallback.png"
png = ROOT / png_rel
if not png.is_file():
    errors.append(f"missing {png_rel}")
else:
    raw = png.read_bytes()
    if len(raw) < 24 or raw[:8] != b"\x89PNG\r\n\x1a\n":
        errors.append(f"{png_rel}: not a valid PNG header")
    else:
        width, height = struct.unpack(">II", raw[16:24])
        if (width, height) != (16, 16):
            errors.append(f"{png_rel}: expected 16x16 fallback sprite, got {width}x{height}")

if errors:
    print("ERROR: polish regression validation failed")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)
print("OK: polish regression guards are present")
