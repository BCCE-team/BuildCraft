#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path
errors = []


def text(rel: str) -> str:
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8", errors="ignore")


def require(rel: str, *tokens: str) -> None:
    source = text(rel)
    for token in tokens:
        if token not in source:
            errors.append(f"{rel}: missing render/Jade regression guard {token!r}")


def forbid(rel: str, *tokens: str) -> None:
    source = text(rel)
    for token in tokens:
        if token in source:
            errors.append(f"{rel}: stale render/Jade pattern {token!r}")


# Quarry frames use a cutout texture with transparent gaps. A single outward-wound cuboid makes the far/internal
# faces disappear through those gaps, so from above only the nearest/top surface is visible. Keep both the centre and
# connection pieces explicitly double-sided, and never attach vanilla cullface hints to their inset geometry.
for rel, reverse_from, reverse_to in (
    (
        "source-shared/src/main/resources/assets/buildcraftbuilders/models/block/frame/base.json",
        '"from": [12, 12, 12]',
        '"to": [4, 4, 4]',
    ),
    (
        "source-shared/src/main/resources/assets/buildcraftbuilders/models/block/frame/connection.json",
        '"from": [12, 12, 4]',
        '"to": [4, 4, 0]',
    ),
    (
        "resource-src/1.21.X/1.21.11/assets/buildcraftbuilders/models/block/frame/base.json",
        '"from": [12, 12, 12]',
        '"to": [4, 4, 4]',
    ),
    (
        "resource-src/1.21.X/1.21.11/assets/buildcraftbuilders/models/block/frame/connection.json",
        '"from": [12, 12, 4]',
        '"to": [4, 4, 0]',
    ),
):
    require(rel, reverse_from, reverse_to)
    forbid(rel, '"cullface"')

# Saved Volume Boxes are dimension-wide; an unloaded distant chunk must not hide the
# entire outline (CORE-22). Draw only the original edge segments in resident client chunks.
# Addon previews still need a nearby resident corner to avoid ghost render outside loaded terrain.
for rel in (
    "source-families/old/src/main/java/buildcraft/core/client/render/RenderVolumeBoxes.java",
    "source-families/1.21.X/src/main/java/buildcraft/core/client/render/RenderVolumeBoxes.java",
    "source-families/26.X/src/main/java/buildcraft/core/client/render/RenderVolumeBoxes.java",
    "source-downports/26.X/26.1.2/family/src/main/java/buildcraft/core/client/render/RenderVolumeBoxes.java",
):
    source = text(rel)
    require(
        rel,
        "LaserBoxRenderer.renderVisibleLaserBoxDynamic(volumeBox.box",
        "if (!isBoxFullyLoaded(volumeBox)) return;",
        "level.getChunkSource().getChunkNow(x, z) != null",
        "Math.abs((long) x - px) <= 16",
        "Math.abs((long) z - pz) <= 16",
    )
    # The laser outline must not be gated by addon-preview visibility.
    outline_start = source.find("ClientVolumeBoxes.INSTANCE.volumeBoxes.forEach(")
    edge_draw = source.find("LaserBoxRenderer.renderVisibleLaserBoxDynamic(", outline_start)
    if outline_start < 0 or edge_draw < 0:
        errors.append(f"{rel}: missing visible volume outline")
    elif "isBoxFullyLoaded(volumeBox)" in source[outline_start:edge_draw]:
        errors.append(f"{rel}: distant unloaded chunks must not hide the entire volume outline")

# The edge renderer must test actual client residency and clip lines at chunk boundaries,
# without inventing new corners or scanning every chunk in a huge volume.
for family in ("old", "1.21.X", "26.X"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/lib/client/render/laser/LaserBoxRenderer.java",
        "public static void renderVisibleLaserBoxDynamic(",
        "Math.abs((long) cx - viewerX) <= 16",
        "Math.abs((long) stationary - observerStationary) > 16",
        "level.getChunkSource().getChunkNow(cx, cz) == null",
        "double a = Math.max(start, chunk * 16.0);",
        "double b = Math.min(end, (chunk + 1.0) * 16.0);",
        "visible.add(new LaserData_BC8(",
        "if (!visible.isEmpty()) LaserRenderer_BC8.renderLaserBoxDynamic(",
    )

# Marker/map-location rendering must distinguish an actually resident client chunk from cached server-side knowledge.
for rel in (
    "source-shared/src/main/java/buildcraft/lib/client/render/MarkerRenderer.java",
    "version-src/1.19.2-forge/src/main/java/buildcraft/lib/client/render/MarkerRenderer.java",
):
    require(rel, "isClientChunkLoaded", "getChunkSource().getChunkNow")
    forbid(rel, "::hasChunkAt")
for platform in ("forge", "neoforge"):
    rel = f"source-platforms/{platform}/src/main/java/buildcraft/core/client/RenderTickListener.java"
    require(rel, "isClientChunkLoaded", "getChunkSource().getChunkNow")
    forbid(rel, "world.hasChunkAt(")

# Wrench rotation changes both the baked/static engine shell and its block-entity-rendered moving assembly. A redraw
# therefore has to invalidate model data and dirty the client render section, with the engine-specific refresh path
# forcing an immediate rebuild after a facing packet.
for platform in ("forge", "neoforge"):
    rel = f"source-platforms/{platform}/src/main/java/buildcraft/lib/tile/TileBC_Neptune.java"
    require(rel, "requestModelDataUpdate();", "Block.UPDATE_CLIENTS")
    forbid(rel, "sendBlockUpdated(worldPosition, state, state, 0)")

    rel = f"source-platforms/{platform}/src/main/java/buildcraft/lib/engine/TileEngineBase_BC8.java"
    require(
        rel,
        "private void refreshEngineModelData()",
        "requestModelDataUpdate();",
        "Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE",
        "if (directionChanged)",
        "refreshEngineModelData();",
    )


# Jade 1.21.11 scans file-level @WailaPlugin annotations once per NeoForge ModContainer.
# BuildCraft intentionally exposes several module ids from one jar, so deduplicate the scan
# result before Jade rejects the repeated class as a fatal duplicate. Keep this shim scoped
# to Jade and to the 1.21.11 NeoForge source band.
require(
    "source-platforms/neoforge/src/main/resources/META-INF/neoforge.mods.toml",
    'config="buildcraft.jade.mixins.json"',
    'requiredMods=["jade"]',
)
require(
    "source-family-platforms/1.21.X/neoforge/src/main/resources/buildcraft.jade.mixins.json",
    '//? source if >=1.21.11',
    '"JadeEntrypointDedupMixin"',
)
require(
    "source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/lib/compat/jade/mixin/JadeEntrypointDedupMixin.java",
    '//? source if >=1.21.11',
    '@Pseudo',
    'targets = "snownee.jade.util.CommonProxy"',
    'method = "loadEntrypoints"',
    'require = 0',
    'seenClasses.add(className)',
    'cir.setReturnValue(List.copyOf(unique));',
)

# Since Minecraft 1.21.6 Jade forbids one object from being both a server data
# provider and a client component provider. Keep those roles split with distinct UIDs.
require(
    "source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    'BlockServerDataProvider.INSTANCE',
    'RobotServerDataProvider.INSTANCE',
    'UID_BLOCK_DATA = id("block_data")',
    'UID_ENTITY_ROBOT_DATA = id("robot_data")',
)
forbid(
    "source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    'implements IBlockComponentProvider, IServerDataProvider',
    'implements IEntityComponentProvider, IServerDataProvider',
)

# Jade should expose native FE storage on BuildCraft blocks in addition to MJ. Do not count the compatibility adapter
# that merely presents an MJ receiver as FE, otherwise a pure MJ battery would be displayed twice in different units.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "source-platforms/neoforge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
):
    require(
        rel,
        "feEnergyGroups(tile)",
        "MjReceiverEnergyStorage",
        'group.id = "fe";',
        'group.getExtraData().putString("Unit", "FE");',
    )

require(
    "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json",
    '"buildcraft.jade.group.fe": "Forge Energy"',
)

# Pipe Jade uses transport throughput rather than the transient internal energy buffers. MJ and FE flows expose
# their existing rolling transfer averages; fluid flow records actual centre-crossing volume in the same 10-tick window.
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java",
        "public long getAverageThroughput()",
        "section.powerAverage.getAverage()",
        "public long getTransferCapacityPerTick()",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowForgeEnergy.java",
        "public int getAverageThroughput()",
        "section.powerAverage.getAverage()",
        "public int getTransferCapacityPerTick()",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowFluids.java",
        "new AverageInt(10)",
        "movedFromCentre = moveFromCenter()",
        "movedToCentre = moveToCenter()",
        "throughputAverage.tick",
        "public int getAverageThroughput()",
        "public int getTransferCapacityPerTick()",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/misc/LocaleUtil.java",
        '"milli.seconds."',
        '"bucket.ticks."',
        "milliBucketsPerTick * 20.0D",
    )

for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "source-platforms/neoforge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
):
    require(
        rel,
        "registration.registerProgress(ProgressProvider.INSTANCE, TilePipeHolder.class);",
        "if (tile instanceof TilePipeHolder)",
        "pipeThroughputGroups(holder)",
        '"pipe_mj_flow", "mj"',
        '"pipe_fe_flow", "fe"',
        '"pipe_fluid_flow", "fluid"',
        'getExtraData().putLong("FlowCurrent"',
        'getExtraData().putLong("FlowCapacity"',
        "LocaleUtil.localizeMjFlow(current)",
        "LocaleUtil.localizeFeFlow(current)",
        "LocaleUtil.localizeFluidFlow(current)",
    )

require(
    "source-shared/src/main/resources/assets/buildcraft/lang/en_us.json",
    '"buildcraft.jade.group.pipe_mj_flow": "MJ throughput"',
    '"buildcraft.jade.group.pipe_fe_flow": "FE throughput"',
    '"buildcraft.jade.group.pipe_fluid_flow": "Fluid throughput"',
    '"buildcraft.fluid.flow.bucket.ticks.short": "%s B/t"',
    '"buildcraft.fluid.flow.milli.seconds.short": "%s mB/s"',
)


# Jade ClientViewGroup titles cause Jade to wrap BCCE storage/progress views in a themed BoxElement.
# Keep these views inline so BuildCraft information uses Jade's normal tooltip background only.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "source-platforms/neoforge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
    "source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/compat/jade/BuildCraftJadePlugin.java",
):
    require(rel, "clientGroup.title = null;")
    forbid(rel, "clientGroup.title = Component.translatable")

# 26.1.2 uses native face lighting for the animated distiller assembly, so legacy
# diffuse shading must not be multiplied a second time or the moving power sprite
# shifts to the wrong colour. Older maintained targets still need multShade().
require(
    "source-families/1.21.X/src/main/java/buildcraft/factory/client/render/RenderDistiller.java",
    "//? if <26.1.2 {",
    "copy.multShade();",
    "buffer.getBuffer(BCRenderTypes.cutout())",
)
forbid(
    "source-families/1.21.X/src/main/java/buildcraft/factory/client/render/RenderDistiller.java",
    "buffer.getBuffer(BCRenderTypes.solid())",
)
require(
    "source-families/1.21.X/src/main/java/buildcraft/factory/block/BlockDistiller.java",
    "//? if >=1.21.11 {",
    ".noOcclusion()",
)

if errors:
    for error in errors:
        print("ERROR:", error)
    sys.exit(1)

print("Render/Jade regression guards OK")
print(" - volume-box outlines clip to resident client chunks; addon previews require a nearby loaded corner")
print(" - marker/map lasers use actual client chunk residency instead of hasChunkAt")
print(" - engine redraw invalidates baked model data and immediately rebuilds facing-dependent geometry")
print(" - Jade 1.21.11 multi-mod entrypoints are deduplicated and server/client providers remain separated")
print(" - Jade exposes native FE storage without duplicating MJ compatibility wrappers")
print(" - energy/fluid pipes show rolling throughput vs effective transfer capacity instead of energy-buffer contents")
print(" - fluid throughput formatting follows the shared per-second/per-tick display setting independently of B/mB units")
