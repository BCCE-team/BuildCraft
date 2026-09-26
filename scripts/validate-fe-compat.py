#!/usr/bin/env python3
"""Static release gate for BuildCraft Community Edition Forge Energy compatibility."""
from __future__ import annotations

from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
from source_lookup import resolve_source_path
errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def text(path: str) -> str:
    p = resolve_source_path(path)
    if not p.is_file():
        fail(f"missing file: {path}")
        return ""
    return p.read_text(encoding="utf-8")


def require(path: str, *needles: str) -> None:
    data = text(path)
    for needle in needles:
        if needle not in data:
            fail(f"{path}: missing {needle!r}")


def require_file(path: str) -> None:
    if not resolve_source_path(path).is_file():
        fail(f"missing file: {path}")


def require_any(path: str, *needles: str) -> None:
    data = text(path)
    if not any(needle in data for needle in needles):
        fail(f"{path}: missing one of {needles!r}")


# Converter recipes and loot, in both resource layouts.
for path in (
    "source-families/legacy/src/main/resources/data/buildcraftenergy/recipes/fe_engine.json",
    "source-families/legacy/src/main/resources/data/buildcraftenergy/recipes/mj_dynamo.json",
    "source-families/modern/src/main/resources/data/buildcraftenergy/recipe/fe_engine.json",
    "source-families/modern/src/main/resources/data/buildcraftenergy/recipe/mj_dynamo.json",
    "source-families/legacy/src/main/resources/data/buildcraftenergy/loot_tables/blocks/mj_dynamo.json",
    "source-families/modern/src/main/resources/data/buildcraftenergy/loot_table/blocks/mj_dynamo.json",
):
    require_file(path)

for path in (
    "source-families/legacy/src/main/resources/data/buildcraftcore/loot_tables/blocks/engine.json",
    "source-families/modern/src/main/resources/data/buildcraftcore/loot_table/blocks/engine.json",
):
    require(path, '"type": "fe"', "buildcraftenergy:engine_fe")

# Original BC8 only had upgrade/undo recipes for wood, cobblestone and stone FE pipes.
for family, recipe_dir in (("legacy", "recipes"), ("modern", "recipe")):
    for stem in ("wood_fe", "cobblestone_fe", "stone_fe"):
        require_file(f"source-families/{family}/src/main/resources/data/buildcrafttransport/{recipe_dir}/{stem}.json")
        require_file(f"source-families/{family}/src/main/resources/data/buildcrafttransport/{recipe_dir}/{stem}_undo.json")

# Nine original RF pipe definitions, exposed as FE.
pipe_ids = (
    "wood_fe", "cobblestone_fe", "stone_fe", "sandstone_fe", "quartz_fe",
    "iron_fe", "gold_fe", "diamond_fe", "diamond_wood_fe",
)
for family in ("legacy", "modern"):
    path = f"source-families/{family}/src/main/java/buildcraft/transport/BCTransportPipes.java"
    data = text(path)
    for pipe_id in pipe_ids:
        if f'"{pipe_id}"' not in data:
            fail(f"{path}: missing FE pipe id {pipe_id}")
    for old_id in (
        "wood_rf", "cobblestone_rf", "stone_rf", "sandstone_rf", "quartz_rf",
        "iron_rf", "gold_rf", "diamond_rf", "diamond_wood_rf",
    ):
        if f'registerAlias("buildcrafttransport:{old_id}"' not in data:
            fail(f"{path}: missing placed-pipe migration alias {old_id}")

# Original BC8 throughput table, renamed FE.
for loader in ("forge", "neoforge"):
    path = f"source-platforms/{loader}/src/main/java/buildcraft/transport/BCTransportConfig.java"
    require(
        path,
        "baseFeRate = 40",
        "cobbleFe, baseFeRate, false",
        "stoneFe, baseFeRate * 2, false",
        "woodFe, baseFeRate * 4, true",
        "sandstoneFe, baseFeRate * 4, false",
        "quartzFe, baseFeRate * 8, false",
        "ironFe, baseFeRate * 8, false",
        "goldFe, baseFeRate * 32, false",
        "diamondFe, baseFeRate * 64, false",
        "diaWoodFe, baseFeRate * 64, true",
    )

# Flow support, gate power-request trigger and limiter actions.
require(
    "source-shared/src/main/java/buildcraft/transport/internal/pipe/IFlowForgeEnergy.java",
    "int getPowerRequested(Direction side);",
)
require(
    "source-shared/src/main/java/buildcraft/transport/statements/TriggerPowerRequested.java",
    "instanceof IFlowForgeEnergy",
)
require(
    "source-shared/src/main/java/buildcraft/transport/statements/TriggerProviderPipes.java",
    "instanceof IFlowForgeEnergy",
    "TRIGGER_POWER_REQUESTED",
)
require(
    "source-shared/src/main/java/buildcraft/transport/statements/ActionPowerLimit.java",
    "ActionIronFeLimit",
    "ActionDiamondFeLimit",
    "iron_rf_s",
    "diamond_rf_s",
)
require(
    "source-shared/src/main/java/buildcraft/transport/statements/ActionProviderPipes.java",
    "ironFe",
    "diamondFe",
)
for loader in ("forge", "neoforge"):
    require(
        f"source-platforms/{loader}/src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourLimiter.java",
        "ActionPowerLimit",
        "limitShift = action.limitShift",
    )
    require(
        f"source-platforms/{loader}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java",
        "MjToFeAutoConverter",
        "EnergyStorage",
    )
    require(
        f"source-platforms/{loader}/src/main/java/buildcraft/lib/internal/mj/MjToFeAutoConverter.java",
        "clampAccepted",
        "Math.max(0, fe.getEnergyStored())",
        "Math.max(0, fe.getMaxEnergyStored())",
    )
    require(
        f"source-platforms/{loader}/src/main/java/buildcraft/transport/pipe/PipeRegistry.java" if loader == "forge"
        else "source-families/modern/src/main/java/buildcraft/transport/pipe/PipeRegistry.java",
        "registerAlias",
    )

# Power-pipe topology must expose the same MJ -> FE auto-conversion path as actual transfer.
forge_power = text("source-platforms/forge/src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java")
connect_start = forge_power.index("public boolean canConnect(Direction face, BlockEntity oTile)")
connect_end = forge_power.index("private void ensureConfigured()", connect_start)
connect_block = forge_power[connect_start:connect_end]
for token in (
    "PlatformStorage.energy(oTile, face.getOpposite())",
    "MjToFeAutoConverter.createReceiver(fe)",
    "converted.canConnect(sections.get(face))",
):
    if token not in connect_block:
        fail(f"Forge PipeFlowPower.canConnect is missing FE auto-convert topology token: {token}")
if "getCapability(ForgeCapabilities.ENERGY" in connect_block:
    fail("Forge PipeFlowPower.canConnect must use PlatformStorage for its FE lookup")

# Limiter sprite sets are present on every supported family/version source.
for path in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/transport/BCTransportSprites.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/transport/BCTransportSprites.java",
    "source-families/modern/src/main/java/buildcraft/transport/BCTransportSprites.java",
):
    require(path, "FE_LIMIT", "trigger_fe_limiter_m256", "trigger_fe_limiter_m0")

# Converter block entities and original numeric characteristics.
for loader in ("forge", "neoforge"):
    engine = f"source-platforms/{loader}/src/main/java/buildcraft/energy/tile/TileEngineFE.java"
    dynamo = f"source-platforms/{loader}/src/main/java/buildcraft/energy/tile/TileDynamoMJ.java"
    require(
        engine,
        "MAX_FE = 10_000",
        "MjAmount.MICRO_MJ_PER_MJ * 4",
        "getMaxChainLength() { return 4; }",
        "protected void burn()",
        "if (power + generatedMj >= getMaxPower()) return;",
        "case BLUE -> 0.04",
        "case GREEN -> 0.05",
        "case YELLOW -> 0.06",
        "case RED -> 0.07",
        "instanceof IItemPipe",
    )
    if "EnumAccess.NONE, EnumPipePart.VALUES" in text(engine):
        fail(f"{engine}: private upgrade inventory must not expose sides when EnumAccess.NONE is used")
    if "EnumAccess.NONE, EnumPipePart.VALUES" in text(dynamo):
        fail(f"{dynamo}: private upgrade inventory must not expose sides when EnumAccess.NONE is used")
    if "protected void engineUpdate()" in text(engine):
        fail(f"{engine}: FE -> MJ conversion must happen in burn(), after the engine send phase, like BC8")
    require(
        dynamo,
        "extends TileEngineBase_BC8",
        "MAX_FE = 10_000",
        "MAX_MJ = 1_000L * MjAmount.MICRO_MJ_PER_MJ",
        "4L * MjAmount.MICRO_MJ_PER_MJ",
        "getMaxChainLength() { return 3; }",
        "MAX_FE / 10",
        "EngineVisualType.MJ_DYNAMO",
        "getFeReceiver",
        "case BLUE -> 0.04",
        "case GREEN -> 0.05",
        "case YELLOW -> 0.06",
        "case RED -> 0.07",
        "instanceof IItemPipe",
    )
    if "convertedInput" in text(dynamo):
        fail(f"{dynamo}: MJ Dynamo must not accept auto-converted FE input; BC8 powerMode excludes dedicated converters")

# Dedicated converters are strictly sided: FE Engine consumes FE on non-output faces and emits MJ through
# currentDirection, while MJ Dynamo consumes MJ on non-output faces and exposes FE only on currentDirection.
for path in (
    "source-platforms/forge/src/main/java/buildcraft/energy/tile/TileEngineFE.java",
    "source-families/modern/src/main/java/buildcraft/energy/tile/TileEngineFE.java",
    "source-downports/modern/1.21.1/family/src/main/java/buildcraft/energy/tile/TileEngineFE.java",
):
    require(
        path,
        "side -> side != currentDirection ? feStorage : null",
        "return side != currentDirection ? Optional.of(api2FeInputPort) : Optional.empty();",
    )
for path in (
    "source-platforms/forge/src/main/java/buildcraft/energy/tile/TileDynamoMJ.java",
    "source-platforms/neoforge/src/main/java/buildcraft/energy/tile/TileDynamoMJ.java",
    "source-family-platforms/modern/neoforge/src/main/java/buildcraft/energy/tile/TileDynamoMJ.java",
):
    require(path, "side == currentDirection ? feStorage : null")

# Dynamo renderer/model parity and non-full collision shape.
require("source-shared/src/main/java/buildcraft/energy/block/BlockDynamoMJ.java", "hasDynamicShape", "getEngineShape")
require("source-shared/src/main/resources/assets/buildcraftenergy/models/block/mj_dynamo.json", "buildcraftlib:block/engine_base")
require(
    "source-shared/src/main/resources/assets/buildcraftenergy/models/item/mj_dynamo.json",
    '"parent": "minecraft:block/block"',
    '"front": "buildcraftenergy:blocks/mj_dynamo/front"',
    '"name": "base_moving"',
    '"name": "chamber"',
)
require_file("source-shared/src/main/resources/assets/buildcraftenergy/models/block/mj_dynamo_texture_probe.json")
for path in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/core/client/render/RenderEngine_BC8.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/core/client/render/RenderEngine_BC8.java",
    "source-platforms/neoforge/src/main/java/buildcraft/core/client/render/RenderEngine_BC8.java",
):
    require(
        path,
        "DYNAMO_BACK", "DYNAMO_FRONT", "DYNAMO_SIDE",
        "getFrontSprite", "EngineVisualType.MJ_DYNAMO",
        "getTrunkLightSprite", "getChamberSprite",
    )

# Engine block entities are common/server code. Client texture/model classes in their signatures make dedicated servers
# fail as soon as converter GameTests instantiate them, even if the offending methods are only used by renderers.
server_engine_paths = [
    "source-platforms/forge/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java",
    "source-platforms/neoforge/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java",
]
for root in (
    ROOT / "source-platforms/forge/src/main/java",
    ROOT / "source-platforms/neoforge/src/main/java",
    ROOT / "source-families/legacy/src/main/java",
    ROOT / "source-families/modern/src/main/java",
    ROOT / "version-src/1.19.2-forge/src/main/java",
    ROOT / "version-src/1.20.1-forge/src/main/java",
):
    if root.is_dir():
        for candidate in root.rglob("*.java"):
            data = candidate.read_text(encoding="utf-8")
            if "extends TileEngineBase_BC8" in data:
                server_engine_paths.append(candidate.relative_to(ROOT).as_posix())
for path in sorted(set(server_engine_paths)):
    data = text(path)
    if "net.minecraft.client." in data or "buildcraft.core.client." in data:
        fail(f"{path}: engine block entity leaks client-only rendering classes onto dedicated server")
for path in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/client/render/RenderDynamoMJ.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/client/render/RenderDynamoMJ.java",
    "source-platforms/neoforge/src/main/java/buildcraft/energy/client/render/RenderDynamoMJ.java",
):
    require(
        path,
        "class RenderDynamoMJ",
        "12x12",
        "renderMovingHead",
        "renderChamber",
        "getTrunkLightSprite",
        "winding consistent with the outward normal",
        "x0, y1, z0",
        "x0, y1, z1",
        "x1, y1, z1",
        "x1, y1, z0",
    )
for path in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/BCEnergyClientProxy.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/BCEnergyClientProxy.java",
    "source-platforms/neoforge/src/main/java/buildcraft/energy/BCEnergyClientProxy.java",
):
    require(path, "BCEnergyClientRenderers.register(", "PlatformClientRegistration.renderers(event)")
require("source-shared/src/main/java/buildcraft/energy/BCEnergyClientRenderers.java",
        "DYNAMO_MJ_TILE.get(), RenderDynamoMJ::new")

# Original converter GUI affordances: power ledger, upgrade hints, battery help and ghost gear overlay.
for path in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/client/gui/GuiEngineFE.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/client/gui/GuiEngineFE.java",
    "source-families/modern/src/main/java/buildcraft/energy/client/gui/GuiEngineFE.java",
):
    require(
        path, "LedgerEngine", "RECT_FE_BATTERY", "FE_UPGRADES", "OVERLAY", "GEAR_IRON", "GEAR_GOLD",
        "getMjPerTick(container.upgrades)"
    )
    require_any(path, "Original BC8 draw order: base GUI -> gear icons -> translucent slot overlay", "addGearIcon(")
for path in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/client/gui/GuiDynamoMJ.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/client/gui/GuiDynamoMJ.java",
    "source-families/modern/src/main/java/buildcraft/energy/client/gui/GuiDynamoMJ.java",
):
    require(
        path, "LedgerDynamoMJ", "RECT_FE_BATTERY", "FE_UPGRADES", "OVERLAY", "GEAR_IRON", "GEAR_GOLD",
        "getMjPerTick(container.upgrades)"
    )
    require_any(path, "Original BC8 draw order: base GUI -> gear icons -> translucent slot overlay", "addGearIcon(")
for path in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/client/gui/LedgerDynamoMJ.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/client/gui/LedgerDynamoMJ.java",
    "source-families/modern/src/main/java/buildcraft/energy/client/gui/LedgerDynamoMJ.java",
):
    require(path, "localizeFeFlow(engine.getCurrentOutput())", "localizeMj(engine.getMjStored())", "localizeHeat(engine.getHeat())")

# Energy buffers must remain persistent even when a tick only changes the converter-specific FE/MJ state.
for loader in ("forge", "neoforge"):
    require(
        f"source-platforms/{loader}/src/main/java/buildcraft/energy/tile/TileEngineFE.java",
        "currentFe -= consumedFe;",
        "markChunkDirty();",
    )
    require(
        f"source-platforms/{loader}/src/main/java/buildcraft/energy/tile/TileDynamoMJ.java",
        "markEnergyStateDirtyIfChanged",
        "persistedFeState",
        "persistedMjState",
    )

# Kinesis pipes keep their classic BC8 power textures; FE pipes use the old RF visual family.
for stem in ("wood", "cobblestone", "stone", "sandstone", "quartz", "gold"):
    power_name = f"{stem}_power" + ("_clear" if stem == "wood" else "") + ".png"
    fe_name = f"{stem}_fe" + ("_clear" if stem == "wood" else "") + ".png"
    power = ROOT / "source-shared/src/main/resources/assets/buildcrafttransport/textures/pipes" / power_name
    fe = ROOT / "source-shared/src/main/resources/assets/buildcrafttransport/textures/pipes" / fe_name
    if not power.is_file(): fail(f"missing Kinesis texture: {power.relative_to(ROOT)}")
    if not fe.is_file(): fail(f"missing FE texture: {fe.relative_to(ROOT)}")
    if power.is_file() and fe.is_file() and power.read_bytes() == fe.read_bytes():
        fail(f"Kinesis texture incorrectly duplicates FE texture: {power_name}")

for path in (
    "source-platforms/forge/src/main/java/buildcraft/lib/gui/help/ElementHelpInfo.java",
    "source-platforms/neoforge/src/main/java/buildcraft/lib/gui/help/ElementHelpInfo.java",
):
    require(path, "contentSignature()")
for path in (
    "source-families/legacy/src/main/java/buildcraft/lib/gui/ledger/LedgerHelp.java",
    "source-families/modern/src/main/java/buildcraft/lib/gui/ledger/LedgerHelp.java",
):
    require(path, "sameContent", "sameTarget", "contentSignature")

require("README.md", "- [x] Add Forge Energy (FE) compatibility")

# Public/new naming is FE. The only old _rf strings permitted are persistence aliases.
allowed_rf_files = {
    ROOT / "source-families/legacy/src/main/java/buildcraft/transport/BCTransportPipes.java",
    ROOT / "source-families/modern/src/main/java/buildcraft/transport/BCTransportPipes.java",
    ROOT / "source-shared/src/main/java/buildcraft/transport/statements/ActionPowerLimit.java",
}
for base in (ROOT / "source-shared", ROOT / "source-families", ROOT / "source-platforms", ROOT / "source-family-platforms", ROOT / "version-src"):
    for p in base.rglob("*"):
        if not p.is_file() or "build" in p.parts:
            continue
        try:
            data = p.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if re.search(r"\bRF\b|Redstone Flux|redstone flux|rf_battery|rf_limit", data):
            fail(f"user-facing/implementation RF branding remains: {p.relative_to(ROOT)}")
        if "_rf" in data and p not in allowed_rf_files:
            fail(f"unexpected old _rf identifier outside migration aliases: {p.relative_to(ROOT)}")

# No known junk/typo textures from upstream's unused resources.
for bad in (
    "source-shared/src/main/resources/assets/buildcrafttransport/textures/pipes/diorite_fe.png",
    "source-shared/src/main/resources/assets/buildcrafttransport/textures/pipes/diamnd_fe_m128.png",
):
    if (ROOT / bad).exists():
        fail(f"unused/typo FE asset present: {bad}")

# Parse every JSON source resource so broken recipe/model edits fail the gate.
for base in (ROOT / "source-shared", ROOT / "source-families", ROOT / "source-platforms", ROOT / "source-family-platforms", ROOT / "version-src"):
    for p in base.rglob("*.json"):
        try:
            # Stonecutter condition headers are removed when a target is materialized;
            # they are source metadata, not part of the JSON payload.
            payload = "\n".join(line for line in p.read_text(encoding="utf-8").splitlines() if not line.startswith("//?"))
            json.loads(payload)
        except Exception as exc:
            fail(f"invalid JSON {p.relative_to(ROOT)}: {exc}")

if errors:
    print("FE compatibility validation FAILED")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("FE compatibility validation OK")
print(" - BC8 MJ <-> FE converters present")
print(" - nine FE pipe types + original rates present")
print(" - original converter and pipe conversion recipes present")
print(" - gate/trigger FE integration present")
print(" - placed RF pipe/action persistence aliases present")
print(" - Kinesis -> FE auto-conversion fallback present")
print(" - MJ Dynamo engine animation/model integration present")
print(" - source JSON resources parse successfully")
