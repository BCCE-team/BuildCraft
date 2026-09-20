#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
SCRIPT_DIR = ROOT / "scripts"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
from source_lookup import resolve_source_path
errors = []

def read(rel):
    p = resolve_source_path(rel)
    if not p.is_file():
        errors.append(f"missing {rel}")
        return ""
    return p.read_text(encoding="utf-8")

def require(rel, *tokens):
    text = read(rel)
    for token in tokens:
        if token not in text:
            errors.append(f"{rel}: missing gameplay regression guard {token!r}")

def forbid(rel, *tokens):
    text = read(rel)
    for token in tokens:
        if token in text:
            errors.append(f"{rel}: forbidden gameplay regression token {token!r}")

for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/silicon/tile/TileIntegrationTable.java",
        "ItemHandlerManager.EnumAccess.EXTRACT",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/robotics/tile/TileRequester.java",
        "RequestMath.comparatorSignal(requested, existingCounts, matching)",
        "RequestMath.acceptedAmount",
        "RequestMath.missingAmount",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/lib/inventory/ItemTransactorHelper.java",
        "Execute source-first",
        "rollbackToSource",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/factory/tile/TileMiner.java",
        "protected long progress",
        ('nbt.putLong("progress", progress)' if platform == "forge" else 'bcData.writeLong("progress", progress)'),
        ('nbt.getLong("progress")' if platform == "forge" else 'bcData.readLong("progress")'),
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/flow/PipeFlowPower.java",
        'getLong("power")', 'getLong("nextPower")',
        'putLong("power"', 'putLong("nextPower"',
        "requiresPeriodicSave()",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/stripes/PipeExtensionManager.java",
        "isCurrentSource",
        "refundStaleRequest",
        "holder.getPipe().getBehaviour() == request.stripes",
    )

require(
    "source-shared/src/main/java/buildcraft/lib/logic/request/RequestMath.java",
    "int satisfied = Math.min(existing[i], need);",
    "progress += (float) satisfied / (float) safeNeed;",
    "Math.floor(progress * 14.0F)",
)

for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotSearchBlock.java",
        "public boolean canLoadFromNBT()",
        "return false;",
    )
    forbid(
        f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotBreak.java",
        "held.mineBlock(",
    )

require(
    "source-shared/src/main/java/buildcraft/robotics/ai/AIRobotSearchAndGotoBlock.java",
    "public boolean shouldSaveToNBT()",
)
require(
    "source-shared/src/main/java/buildcraft/robotics/internal/legacy/robots/AIRobot.java",
    "delegateAI.shouldSaveToNBT()",
)

# Water Gel is intentionally random-tick driven; stale scheduled-tick calls must not return.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/factory/block/BlockWaterGel.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/factory/block/BlockWaterGel.java",
    "source-platforms/neoforge/src/main/java/buildcraft/factory/block/BlockWaterGel.java",
):
    forbid(rel, "scheduleTick(")

if errors:
    print("ERROR: gameplay regression validation failed")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)
print("OK: gameplay regression guards are present")
