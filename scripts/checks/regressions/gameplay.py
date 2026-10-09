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

for family in ("old", "1.21.X"):
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

# 1.21.11 changed the collision hook to canBeCollidedWith(Entity), and 26.1.2 folded
# interactAt into Entity#interact(Player, hand, location). Keep both modern signatures guarded so
# robots remain solid to players and can still be dismantled with a wrench.
require(
    "source-family-platforms/1.21.X/neoforge/src/main/java/buildcraft/robotics/entity/EntityRobot.java",
    "public boolean canBeCollidedWith(@Nullable Entity other)",
    "public InteractionResult interact(Player player, InteractionHand hand, Vec3 location)",
    "WrenchUtil.isWrench(stack)",
    "onRobotHit(false)",
)
require(
    "source-downports/1.21.X/1.21.1/neoforge/src/main/java/buildcraft/robotics/entity/EntityRobot.java",
    "public boolean canBeCollidedWith()",
    "public InteractionResult interact(Player player, InteractionHand hand)",
)

# Common gear tags are the public inter-mod contract. Keep every BuildCraft tier in both the
# aggregate c:gears tag and its material-specific tag; c:gears/wooden is retained as a widespread
# alias for packs/mods that use the adjective form.
for family, item_dir in (("old", "items"), ("1.21.X", "item")):
    tag_root = f"source-families/{family}/src/main/resources/data/c/tags/{item_dir}"
    require(
        f"{tag_root}/gears.json",
        "#c:gears/wood",
        "#c:gears/stone",
        "#c:gears/iron",
        "#c:gears/gold",
        "#c:gears/diamond",
    )
    for material, gear in (
        ("wood", "gear_wood"),
        ("stone", "gear_stone"),
        ("iron", "gear_iron"),
        ("gold", "gear_gold"),
        ("diamond", "gear_diamond"),
    ):
        require(f"{tag_root}/gears/{material}.json", f"buildcraftcore:gears/{gear}")
    require(f"{tag_root}/gears/wooden.json", "#c:gears/wood")

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
