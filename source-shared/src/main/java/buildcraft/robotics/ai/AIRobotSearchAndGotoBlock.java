package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

public class AIRobotSearchAndGotoBlock extends AIRobot {
    private BlockIndex blockFound;
    private IBlockFilter filter;
    private boolean random;
    private double maxDistanceToEnd;
    private boolean nearestByRoute;
    /** True only after the owning board has been notified of successful arrival. */
    private boolean keepReservationForBoard;
    private RobotProcessorWorkScheduler.SuggestedWork processorHint;
    private java.util.List<net.minecraft.core.BlockPos> queuedMiningTargets = java.util.List.of();
    private boolean searchingQueuedOre;

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
            // A nearby handoff is instantaneous; otherwise use a single
            // multi-target search for the shortest navigable route to the
            // processor's queued ore. An unreachable queue is reported to the
            // processor rather than followed by an expensive world-wide BFS.
            queuedMiningTargets = RobotProcessorWorkScheduler.queuedMiningTargets(robot);
            searchingQueuedOre = !queuedMiningTargets.isEmpty();
            // When a miner is already beside the next ore, do not spin up a
            // fresh large BFS. Keep the same authoritative registry claim.
            if (tryNearbyQueuedOre()) return;
            startDelegateAI(new AIRobotSearchNearestBlock(robot, filter, maxDistanceToEnd,
                    queuedMiningTargets));
        } else {
            // Stage 2: one shared, provisional hint. The normal scanner remains the
            // fallback if the block, permission, route or tool has changed.
            // Workers with a prepared queue try its actual jobs first, using
            // the same pathfinder and final registry claim as autonomous work.
            // If the queue is still warming up, the native scanner keeps going.
            java.util.List<BlockPos> queued = !random && filter != null
                    ? RobotProcessorWorkScheduler.queuedWorkTargets(robot) : java.util.List.of();
            processorHint = queued.isEmpty() && !random && filter != null
                    ? RobotProcessorWorkScheduler.suggest(robot) : null;
            startDelegateAI(new AIRobotSearchBlock(robot, random, filter, maxDistanceToEnd,
                    !queued.isEmpty() ? queued
                            : processorHint == null ? java.util.List.of() : java.util.List.of(processorHint.position())));
        }
    }

    /** A bounded, exact BFS around the current robot for immediate queue hand-off.
     * If the target needs a longer path, use the original full-range BFS below. */
    private boolean tryNearbyQueuedOre() {
        if (queuedMiningTargets.isEmpty() || robot.getRegistry() == null || filter == null) return false;
        //? if <1.20 {
        Level level = robot.level;
        //?} else {
        /*?
        Level level = robot.level();
        ?*/
        //?}
        BlockPos start = robot.blockPosition();
        var workZone = robot.getZoneToWork();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Set<BlockPos> targets = new HashSet<>(queuedMiningTargets);
        pending.add(start);
        previous.put(start, null);
        int expanded = 0;
        while (!pending.isEmpty() && expanded++ < 64) {
            BlockPos here = pending.removeFirst();
            int depth = 0;
            for (BlockPos at = here; previous.get(at) != null; at = previous.get(at)) depth++;
            for (Direction direction : Direction.values()) {
                BlockPos next = here.relative(direction);
                if (next.getY() < level.getMinBuildHeight() || next.getY() >= level.getMaxBuildHeight()
                        || !level.isLoaded(next)) continue;
                BlockState state = level.getBlockState(next);
                if (!state.isAir() && targets.contains(next)
                        && RobotProcessorWorkScheduler.allowsRobotTarget(robot, next)
                        && (workZone == null || workZone.contains(new net.minecraft.world.phys.Vec3(
                                next.getX() + .5D, next.getY() + .5D, next.getZ() + .5D)))
                        && filter.matches(level, next)
                        && !robot.getRegistry().isTaken(new ResourceIdBlock(next))
                        && robot.getRegistry().take(new ResourceIdBlock(next), robot)) {
                    java.util.LinkedList<BlockIndex> route = new java.util.LinkedList<>();
                    for (BlockPos at = here; !at.equals(start); at = previous.get(at)) {
                        route.addFirst(new BlockIndex(at));
                    }
                    acceptTarget(new BlockIndex(next), route);
                    return true;
                }
                if (depth < 3 && !previous.containsKey(next)
                        && (state.isAir() || state.getCollisionShape(level, next).isEmpty())) {
                    previous.put(next, here);
                    pending.add(next);
                }
            }
        }
        return false;
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
            } else if (searchingQueuedOre || search.usedProcessorQueue()) {
                searchingQueuedOre = false;
                // The nearest-search AI may have adopted the queue *after* its
                // parent was started; its snapshot is then authoritative.
                RobotProcessorWorkScheduler.queuedMiningSearchFailed(robot,
                        search.usedProcessorQueue() ? search.processorQueueTargets() : queuedMiningTargets);
                queuedMiningTargets = java.util.List.of();
                if (RobotProcessorWorkScheduler.isManagedMiner(robot)) {
                    // A failed queued search already gave the panel the list
                    // of unreachable deposits. Do not immediately run another
                    // 262k-node unrestricted BFS over the same air volume.
                    setSuccess(false);
                    terminate();
                } else {
                    startDelegateAI(new AIRobotSearchNearestBlock(robot, filter, maxDistanceToEnd,
                            java.util.List.of()));
                }
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
        if (route != null && target != null) {
            RobotProcessorWorkScheduler.targetAcquired(robot, target.toBlockPos());
        }
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
        RobotProcessorWorkScheduler.releaseHint(robot, processorHint);
        processorHint = null;
        // terminate() and abort() both run end(); navigation may be cancelled by
        // gates, loss of station, unload, or a newly selected board.
        if (!keepReservationForBoard) {
            if (blockFound != null) RobotProcessorWorkScheduler.targetAbandoned(robot, blockFound.toBlockPos());
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
