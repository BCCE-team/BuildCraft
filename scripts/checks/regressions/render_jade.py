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


# Volume boxes are dimension-synchronized saved data, not chunk-owned marker cache entries. Keep the data cached,
# but hide the entire laser box unless every chunk intersecting it is actually resident on the client.
for family in ("legacy", "modern"):
    rel = f"source-families/{family}/src/main/java/buildcraft/core/client/render/RenderVolumeBoxes.java"
    require(
        rel,
        "isBoxFullyLoaded(volumeBox)",
        "level.getChunkSource().getChunkNow(chunkX, chunkZ) == null",
        "int minChunkX",
        "int maxChunkZ",
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
        'case "robot", "inventory", "tank", "robot_tank", "robot_energy", "mj", "fe", "zone_planner", "laser"',
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

if errors:
    for error in errors:
        print("ERROR:", error)
    sys.exit(1)

print("Render/Jade regression guards OK")
print(" - saved volume boxes render only while all intersecting client chunks are resident")
print(" - marker/map lasers use actual client chunk residency instead of hasChunkAt")
print(" - engine redraw invalidates baked model data and immediately rebuilds facing-dependent geometry")
print(" - Jade exposes native FE storage without duplicating MJ compatibility wrappers")
print(" - energy/fluid pipes show rolling throughput vs effective transfer capacity instead of energy-buffer contents")
print(" - fluid throughput formatting follows the shared per-second/per-tick display setting independently of B/mB units")
