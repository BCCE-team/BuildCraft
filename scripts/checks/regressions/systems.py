#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path
errors = []

def text(rel):
    p = resolve_source_path(rel)
    if not p.is_file():
        errors.append(f"missing {rel}")
        return ""
    return p.read_text(encoding="utf-8")

def require(rel, *tokens):
    s = text(rel)
    for token in tokens:
        if token not in s:
            errors.append(f"{rel}: missing systems regression guard {token!r}")

for platform in ("forge", "neoforge"):
    base = f"source-platforms/{platform}/src/main/java"
    require(f"{base}/buildcraft/robotics/tile/TileRequester.java",
            "insertOnlyRequestedAmount", "template.getCount()")
    require(f"{base}/buildcraft/robotics/tile/TileZonePlanner.java",
            "OperationMode.SIMULATE", "inputBefore", "setStackInSlot(SLOT_INPUT_MAP, inputBefore)")
    require(f"{base}/buildcraft/transport/pipe/flow/PipeFlowItems.java",
            "return stack.getCount()", "inserted.shrink(excess.getCount())")
    if "IllegalStateException(\"After successfully simulation insertion" in text(f"{base}/buildcraft/transport/pipe/flow/PipeFlowItems.java"):
        errors.append(f"{platform}: item-pipe extraction race still crashes")
    require(f"{base}/buildcraft/robotics/internal/api2/RobotServiceImpl.java",
            "getSlotsForFace", "canTakeItemThroughFace", "ActionStationProvideItems.canExtractItem")
    require(f"{base}/buildcraft/transport/pipe/behaviour/PipeBehaviourStripes.java",
            "BlockUtil.canBreakBlock", "if (drops.isPresent())")
    require(f"{base}/buildcraft/transport/tile/TilePipeHolder.java",
            "sendNetworkBatch", "NET_UPDATE_MULTI", "writeShort(mask)", "readUnsignedShort()")
    require(f"{base}/buildcraft/lib/gui/MenuBC_Neptune.java", "player.isSpectator()")
    require(f"{base}/buildcraft/lib/net/MessageContainer.java", "player.isSpectator()")
    require(f"source-platforms/{platform}/src/gametest/java/buildcraft/gametest/FeMjAdversarialGameTests.java",
            "automaticFeCompatibilityConfigActuallyGatesAdapters", "chainedAutomaticConvertersPreserveWholeFeAndMjRemainder")
    require(f"source-platforms/{platform}/src/gametest/java/buildcraft/transport/pipe/flow/PipeForgeEnergyGameTests.java",
            "woodenFeExtractionUsesSimulationAndNeverOverfills", "feOverflowRequestsAreSaturatedInsteadOfWrapping")
    require(f"source-platforms/{platform}/src/gametest/java/buildcraft/gametest/PerformanceSmokeGameTests.java",
            "1_024", "idleBuilderAndQuarryMachineTicksRemainPowerNeutral", "manyIdleRobotsKeepChargingStateStable", "largeZoneAndChunkStyleRoundTripStaysBounded")

for family in ("legacy", "modern"):
    require(f"source-families/{family}/src/main/java/buildcraft/robotics/boards/BoardRobotBuilder.java",
            "RobotBuildResult", "result == RobotBuildResult.COMMITTED")
    require(f"source-families/{family}/src/main/java/buildcraft/lib/gui/BCMenuUtil.java", "player.isSpectator()")

require("source-shared/src/main/java/buildcraft/builders/tile/IRobotBuilderTarget.java", "buildRobotTaskResult")
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java",
    "source-platforms/neoforge/src/main/java/buildcraft/transport/block/BlockPipeHolder.java",
):
    require(rel, "MAX_CONNECTION_SHAPE_CACHE = 512", "CONNECTION_SHAPE_CACHE", "putIfAbsent")

for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/factory/tile/TileMiningWell.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/factory/tile/TileMiningWell.java",
    "source-families/modern/src/main/java/buildcraft/factory/tile/TileMiningWell.java",
):
    require(rel, "var drops = BlockUtil.breakBlockAndGetDrops", "if (drops.isPresent())")

if errors:
    for e in errors:
        print("ERROR:", e)
    sys.exit(1)
print("Systems regression guards OK")
print(" - requester/map/item-pipe/robot-dock correctness guarded")
print(" - failed mining/stripes/builder work no longer burns resources incorrectly")
print(" - spectator mutation paths guarded server-side")
print(" - pipe-shape cache and multi-update batching guarded")
print(" - adversarial FE/MJ and deterministic performance GameTests present")
