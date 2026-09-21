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
    path = resolve_source_path(rel)
    if not path.is_file():
        errors.append(f"missing {rel}")
        return ""
    return path.read_text(encoding="utf-8")


def require(rel, *tokens):
    text = read(rel)
    for token in tokens:
        if token not in text:
            errors.append(f"{rel}: missing integrity regression guard {token!r}")


def forbid(rel, *tokens):
    text = read(rel)
    for token in tokens:
        if token in text:
            errors.append(f"{rel}: forbidden integrity regression token {token!r}")


# Exact-stack semantics: preserve the 1.19 reference branch, but require NBT/components on newer targets.
require(
    "source-families/legacy/src/main/java/buildcraft/lib/inventory/filter/ArrayStackFilter.java",
    "ItemStack.isSame(s, stack)",
    "ItemStack.isSameItemSameTags(s, stack)",
)
require(
    "source-families/modern/src/main/java/buildcraft/lib/inventory/filter/ArrayStackFilter.java",
    "ItemStack.isSameItemSameComponents(s, stack)",
)
for rel in (
    "source-platforms/forge/src/main/java/buildcraft/factory/tile/TileAutoWorkbenchBase.java",
    "source-platforms/forge/src/main/java/buildcraft/silicon/tile/TileAdvancedCraftingTable.java",
):
    require(rel, "ItemStack.isSame(before, after)", "ItemStack.isSameItemSameTags(before, after)")
for rel in (
    "source-platforms/neoforge/src/main/java/buildcraft/factory/tile/TileAutoWorkbenchBase.java",
    "source-platforms/neoforge/src/main/java/buildcraft/silicon/tile/TileAdvancedCraftingTable.java",
):
    require(rel, "ItemStack.isSameItemSameComponents(before, after)")
    forbid(rel, "ItemStack.isSameItem(before, after)")

# Stripes: result-aware placement, real block-pass dispatch, and entity/block API2 permissions.
require(
    "source-shared/src/main/java/buildcraft/transport/stripes/StripesHandlerPlaceBlock.java",
    "WorldOperationKind.BLOCK_PLACE",
    "OperationMode.EXECUTE",
    ").consumesAction();",
)
require(
    "source-shared/src/main/java/buildcraft/transport/stripes/StripesHandlerEntityInteract.java",
    "WorldOperationKind.ENTITY_INTERACT",
    "OperationMode.EXECUTE",
    ".consumesAction()",
)
for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotStripesHandler.java",
        "return stack.getItem() instanceof BlockItem;",
        "WorldOperationKind.BLOCK_PLACE, OperationMode.EXECUTE",
    )
require(
    "source-shared/src/main/java/buildcraft/transport/stripes/StripesHandlerMinecartDestroy.java",
    "BlockPos target = pos.relative(direction);",
    "new AABB(target)",
    "WorldOperationKind.ENTITY_ATTACK",
    "OperationMode.EXECUTE",
)
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/transport/pipe/behaviour/PipeBehaviourStripes.java",
        "StripesRegistry.INSTANCE.handleBlock(world, pos, direction, blockPlayer, this)",
    )

# Robotics must authorize the actual mutation, not only a simulation/preflight.
for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotBreak.java",
        "RobotAutomationSupport.actor(robot), OperationMode.SIMULATE",
        "RobotAutomationSupport.actor(robot), OperationMode.EXECUTE",
    )
    require(
        f"source-families/{family}/src/main/java/buildcraft/robotics/ai/AIRobotUseToolOnBlock.java",
        "RobotAutomationSupport.actor(robot), OperationMode.SIMULATE",
        "RobotAutomationSupport.actor(robot), OperationMode.EXECUTE",
    )
require(
    "source-shared/src/main/java/buildcraft/robotics/ai/AIRobotAttack.java",
    "WorldOperationKind.ENTITY_ATTACK",
    "OperationMode.EXECUTE",
    "RobotAutomationSupport.permitsEntity",
)

# Tank column connectivity must inspect both half-columns, not just two adjacent block contents.
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/factory/tile/TileTank.java",
        "findColumnFluid(this, direction.getOpposite())",
        "findColumnFluid(other, direction)",
        "fluidsCanShareColumn",
    )

# Combustion residue cannot grow an unbounded invisible buffer after the visible tank fills.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
    "source-platforms/neoforge/src/main/java/buildcraft/energy/tile/TileEngineIron_BC8.java",
):
    require(
        rel,
        "flushPendingResidue()",
        "canConsumeFuelWithResidue()",
        "residueBlocked = true;",
        "double pendingAfterNextFuel = residueAmount + produced;",
        "int freeCapacity = Math.max(0, tankResidue.getCapacity() - tankResidue.getFluidAmount());",
    )

# Filler load order: preserve builder/addon NBT until the level and BuildingInfo are valid.
for platform in ("forge", "neoforge"):
    rel = f"source-platforms/{platform}/src/main/java/buildcraft/builders/tile/TileFiller.java"
    require(
        rel,
        "private CompoundTag pendingBuilderNbt;",
        "private UUID pendingAddonVolumeBoxId;",
        ("pendingBuilderNbt = nbt.contains(\"builder\")" if platform == "forge"
         else "pendingBuilderNbt = bcData.has(\"builder\")"),
        "updateBuildingInfo();",
        "activeBuilder.deserializeNBT(pendingBuilderNbt);",
    )

# Quarry collision children must use a cached membership set rather than rebuild the full mask map per child tick.
for rel in (
    "version-src/1.19.2-forge/src/main/java/buildcraft/builders/tile/TileQuarry.java",
    "version-src/1.20.1-forge/src/main/java/buildcraft/builders/tile/TileQuarry.java",
    "source-platforms/neoforge/src/main/java/buildcraft/builders/tile/TileQuarry.java",
):
    require(rel, "ensureCollisionBlocksCurrent();", "return collisionBlockPoses.contains(pos);")
    forbid(rel, "return buildCollisionBlockMasks().containsKey(pos);")

# Blueprint storage ownership: clients own the browsable/shareable library; dedicated servers persist the same
# full .bcnbt only as machine-side construction storage. Generic cache requests remain transient and must not
# implicitly add server-known blueprints to the client's library.
require(
    "source-shared/src/main/java/buildcraft/builders/snapshot/SnapshotCreationPersistence.java",
    "Snapshot persistedSnapshot = snapshot.copy();",
    "persistedSnapshot.key = new Snapshot.Key(persistedSnapshot.key, header);",
    "GlobalSavedDataSnapshots.cacheServerSnapshot(level, persistedSnapshot);",
    "new MessageSnapshotResponse(persistedSnapshot)",
)
for platform in ("forge", "neoforge"):
    storage = f"source-platforms/{platform}/src/main/java/buildcraft/builders/snapshot/GlobalSavedDataSnapshots.java"
    require(
        storage,
        "private List<Snapshot.Key> readClientList()",
        "return List.of();",
        "Snapshot existing = readSnapshotFile(snapshotFile);",
        "existing.key.header == null && checked.key.header != null",
    )
    forbid(storage, "checked.key = new Snapshot.Key(checked.key, (Snapshot.Header) null);")

    library = f"source-platforms/{platform}/src/main/java/buildcraft/builders/tile/TileElectronicLibrary.java"
    require(
        library,
        "Snapshot.Header header = snapshot.key.header;",
        "snapshot.computeKey();",
        "GlobalSavedDataSnapshots.cacheServerSnapshot(level, snapshot);",
    )
    forbid(library, "snapshot.key = new Snapshot.Key(snapshot.key, (Snapshot.Header) null);")

for family in ("legacy", "modern"):
    require(
        f"source-families/{family}/src/main/java/buildcraft/builders/snapshot/MessageSnapshotRequest.java",
        "Snapshot transientSnapshot = snapshot.copy();",
        "transientSnapshot.key = new Snapshot.Key(transientSnapshot.key, (Snapshot.Header) null);",
        "new MessageSnapshotResponse(transientSnapshot)",
    )

# Snapshot requests: no directory-wide scan per miss, bounded server cache, and per-player request throttling.
require(
    "source-shared/src/main/java/buildcraft/builders/snapshot/SnapshotRequestLimiter.java",
    "MAX_REQUESTS_PER_WINDOW = 32",
    "System.nanoTime()",
)
for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/builders/snapshot/GlobalSavedDataSnapshots.java",
        ".maximumSize(512)",
        "new File(snapshotsFile, key.toString() + SNAPSHOT_FILE_EXTENSION)",
    )
    require(
        f"source-platforms/{platform}/src/main/java/buildcraft/builders/snapshot/MessageSnapshotRequest.java",
        "SnapshotRequestLimiter.allow",
    )

for platform in ("forge", "neoforge"):
    require(
        f"source-platforms/{platform}/src/gametest/java/buildcraft/gametest/GameplayIntegrityGameTests.java",
        "mixedFluidColumnsDoNotMergeThroughEmptyBridgeTank",
        "stripesBlockPlacementReportsRejectedUseOnAsFailure",
    )

if errors:
    print("ERROR: integrity regression validation failed")
    for error in errors:
        print(" -", error)
    raise SystemExit(1)
print("OK: integrity regression guards are present")
