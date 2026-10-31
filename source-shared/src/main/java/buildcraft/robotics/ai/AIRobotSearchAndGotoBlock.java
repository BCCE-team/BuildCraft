package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import net.minecraft.nbt.CompoundTag;

public class AIRobotSearchAndGotoBlock extends AIRobot {
    private BlockIndex blockFound;
    private IBlockFilter filter;
    private boolean random;
    private double maxDistanceToEnd;
    private boolean nearestByRoute;
    /** True only after the owning board has been notified of successful arrival. */
    private boolean keepReservationForBoard;

    public AIRobotSearchAndGotoBlock(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotSearchAndGotoBlock(EntityRobotBase robot, boolean random, IBlockFilter filter) {
        this(robot, random, filter, 0);
    }

    public AIRobotSearchAndGotoBlock(EntityRobotBase robot, boolean random, IBlockFilter filter, double maxDistanceToEnd) {
        this(robot, random, filter, maxDistanceToEnd, false);
    }

    /** Only boards explicitly requesting nearest-route search use the incremental BFS. */
    public AIRobotSearchAndGotoBlock(EntityRobotBase robot, boolean random, IBlockFilter filter,
            double maxDistanceToEnd, boolean nearestByRoute) {
        this(robot);
        this.random = random;
        this.filter = filter;
        this.maxDistanceToEnd = maxDistanceToEnd;
        this.nearestByRoute = nearestByRoute;
    }

    @Override
    public void start() {
        if (nearestByRoute && !random) {
            startDelegateAI(new AIRobotSearchNearestBlock(robot, filter, maxDistanceToEnd));
        } else {
            startDelegateAI(new AIRobotSearchBlock(robot, random, filter, maxDistanceToEnd));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchBlock search) {
            if (search.success() && search.takeResource()) {
                acceptTarget(search.blockFound, search.path);
            } else {
                setSuccess(false);
                terminate();
            }
        } else if (ai instanceof AIRobotSearchNearestBlock search) {
            if (search.success() && search.takeResource()) {
                acceptTarget(search.blockFound, search.path);
            } else {
                setSuccess(false);
                terminate();
            }
        } else if (ai instanceof AIRobotGotoBlock) {
            if (ai.success() && blockFound != null) {
                // The board's delegateAIEnded takes over this reservation.
                keepReservationForBoard = true;
            } else {
                setSuccess(false);
            }
            terminate();
        }
    }

    private void acceptTarget(BlockIndex target, java.util.LinkedList<BlockIndex> route) {
        blockFound = target;
        if (route == null) {
            setSuccess(false);
            terminate();
        } else if (route.isEmpty()) {
            // Already adjacent: a zero-waypoint route must not be treated as
            // unreachable by AIRobotGotoBlock. Hand the reservation to the board.
            keepReservationForBoard = true;
            terminate();
        } else {
            startDelegateAI(new AIRobotGotoBlock(robot, route));
        }
    }

    @Override
    public void end() {
        // terminate() and abort() both run end(); navigation may be cancelled by
        // gates, loss of station, unload, or a newly selected board.
        if (!keepReservationForBoard) {
            releaseBlockFound();
        }
    }

    private void releaseBlockFound() {
        if (blockFound != null && robot.getRegistry() != null) {
            robot.getRegistry().release(new ResourceIdBlock(blockFound));
            blockFound = null;
        }
    }

    public BlockIndex getBlockFound() {
        return blockFound;
    }

    @Override
    public boolean success() {
        // Finding a target does not make the entire task successful. The robot
        // must actually complete its route; propagate a failed GotoBlock through
        // to all generic block workers (including Miner and Lumberjack).
        return super.success() && blockFound != null;
    }

    @Override
    public boolean canLoadFromNBT() {
        // A saved instance is self-contained: write-side filtering below guarantees that blockFound has already been
        // resolved and any delegate is a serializable GotoBlock. This must remain true before loadSelfFromNBT runs.
        return true;
    }

    @Override
    public boolean shouldSaveToNBT() {
        // Before a target is reserved the search predicate only exists as a runtime closure, so the owning board must
        // recreate this AI after reload. Once blockFound is set, the reservation and GotoBlock can safely be persisted.
        return blockFound != null;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        if (blockFound != null) {
            CompoundTag tag = new CompoundTag();
            blockFound.writeTo(tag);
            nbt.put("indexStored", tag);
        }
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        // Ownership is transferred only in the success callback, never in NBT.
        keepReservationForBoard = false;
        if (nbt.contains("indexStored")) {
            blockFound = new BlockIndex(nbt.getCompound("indexStored"));
        }
    }
}
