package buildcraft.robotics.boards;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotBreak;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ItemAbilities;

/**
 * Cuts one connected tree before selecting another one.
 *
 * <p>The generic block worker searches again after every broken block. For logs this caused a lumberjack to break
 * one or two blocks, then select another tree because a log at the robot's current Y level was encountered before
 * the next log above the current trunk. The lumberjack now remembers the connected log component selected by the
 * first search and limits subsequent searches to that component until it is finished or unreachable.</p>
 */
public class BoardRobotLumberjack extends BoardRobotGenericBreakBlock {
    private static final int MAX_TREE_LOGS = 2048;
    private static final int MAX_TREE_HORIZONTAL_RADIUS = 24;
    private static final int MAX_TREE_VERTICAL_RADIUS = 48;

    private final Set<BlockPos> currentTree = new HashSet<>();
    private BlockPos treeOrigin;

    public BoardRobotLumberjack(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("lumberjack").nbt();
    }

    @Override
    public boolean isExpectedTool(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof AxeItem || stack.canPerformAction(ItemAbilities.AXE_DIG));
    }

    @Override
    public void update() {
        pruneCurrentTree();

        // If a save contains the reserved log without a tree cache, rebuild the cache lazily.
        if (currentTree.isEmpty() && blockFound() != null) {
            collectConnectedTree(blockFound().toBlockPos());
        }

        super.update();
    }

    @Override
    public boolean isExpectedBlock(Level level, BlockPos pos) {
        if (!isValidTreeLog(level, pos)) {
            return false;
        }
        return currentTree.isEmpty() || currentTree.contains(pos);
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        // If the remembered tree no longer has a reachable log, forget it immediately and search normally next tick
        // instead of sleeping for the full idle delay while other trees are available.
        if (ai instanceof AIRobotSearchAndGotoBlock search && !search.success() && !currentTree.isEmpty()) {
            clearCurrentTree();
            return;
        }

        BlockIndex completedBlock = ai instanceof AIRobotBreak ? blockFound() : null;
        super.delegateAIEnded(ai);

        if (ai instanceof AIRobotSearchAndGotoBlock search && search.success() && currentTree.isEmpty()) {
            BlockIndex found = blockFound();
            if (found != null) {
                collectConnectedTree(found.toBlockPos());
            }
        } else if (ai instanceof AIRobotBreak && completedBlock != null) {
            currentTree.remove(completedBlock.toBlockPos());
            if (currentTree.isEmpty()) {
                treeOrigin = null;
            }
        }
    }

    private void collectConnectedTree(BlockPos root) {
        clearCurrentTree();
        Level level = robot.level();
        if (!isValidTreeLog(level, root)) {
            return;
        }

        treeOrigin = root.immutable();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        open.add(treeOrigin);

        while (!open.isEmpty() && currentTree.size() < MAX_TREE_LOGS) {
            BlockPos pos = open.removeFirst();
            if (!visited.add(pos) || !isInsideTreeScanBounds(pos) || !isValidTreeLog(level, pos)) {
                continue;
            }

            currentTree.add(pos.immutable());

            // Vanilla trees can contain diagonal branches, so face-only traversal is not enough for acacia,
            // large oak and jungle trees. A bounded 3x3x3 traversal catches those branches without allowing a
            // lumberjack to scan an unlimited player-built log network in one tick.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos next = pos.offset(dx, dy, dz);
                        if (!visited.contains(next) && isInsideTreeScanBounds(next)) {
                            open.addLast(next);
                        }
                    }
                }
            }
        }
    }

    private void pruneCurrentTree() {
        if (currentTree.isEmpty()) {
            treeOrigin = null;
            return;
        }

        Level level = robot.level();
        currentTree.removeIf(pos -> level.isLoaded(pos) && !isValidTreeLog(level, pos));
        if (currentTree.isEmpty()) {
            treeOrigin = null;
        }
    }

    private boolean isValidTreeLog(Level level, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.getBlockState(pos).is(BlockTags.LOGS)) {
            return false;
        }

        IZone zone = robot.getZoneToWork();
        if (zone != null && !zone.contains(new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))) {
            return false;
        }

        return matchesGateFilter(level, pos);
    }

    private boolean isInsideTreeScanBounds(BlockPos pos) {
        if (treeOrigin == null) {
            return false;
        }
        return Math.abs(pos.getX() - treeOrigin.getX()) <= MAX_TREE_HORIZONTAL_RADIUS
                && Math.abs(pos.getZ() - treeOrigin.getZ()) <= MAX_TREE_HORIZONTAL_RADIUS
                && Math.abs(pos.getY() - treeOrigin.getY()) <= MAX_TREE_VERTICAL_RADIUS;
    }

    private void clearCurrentTree() {
        currentTree.clear();
        treeOrigin = null;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        super.writeSelfToNBT(nbt);
        if (treeOrigin != null) {
            nbt.putLong("treeOrigin", treeOrigin.asLong());
        }
        if (!currentTree.isEmpty()) {
            long[] positions = new long[currentTree.size()];
            int index = 0;
            for (BlockPos pos : currentTree) {
                positions[index++] = pos.asLong();
            }
            nbt.putLongArray("currentTree", positions);
        }
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        super.loadSelfFromNBT(nbt);
        clearCurrentTree();

        if (nbt.contains("treeOrigin")) {
            treeOrigin = BlockPos.of(nbt.getLong("treeOrigin"));
        }
        for (long packedPos : nbt.getLongArray("currentTree")) {
            currentTree.add(BlockPos.of(packedPos));
        }

        if (currentTree.isEmpty()) {
            treeOrigin = null;
        } else if (treeOrigin == null) {
            treeOrigin = currentTree.iterator().next();
        }
    }
}
