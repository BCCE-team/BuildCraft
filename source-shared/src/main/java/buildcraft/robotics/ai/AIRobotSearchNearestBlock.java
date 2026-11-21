package buildcraft.robotics.ai;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.Comparator;
import java.util.PriorityQueue;

import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Finds the nearest reachable work block by actual grid-path length, rather than
 * the order in which a chunk/ring scanner encounters matching blocks.
 *
 * <p>Autonomous miners use breadth-first search over passable positions. For
 * processor-assigned deposits, a single multi-target A* traversal prioritises
 * corridors leading toward known ore without choosing a target solely by
 * Euclidean distance. Each search picks a reachable block by shortest navigable
 * path within its budget. Work-zone limits apply to ore targets, not corridors.</p>
 *
 * <p>Like the other robot searches, this does not load chunks or search without
 * bounds. The node budget is spread across game ticks to protect server TPS.</p>
 */
public class AIRobotSearchNearestBlock extends AIRobot {
    private static final int DEFAULT_SEARCH_RADIUS = 96;
    private static final int MAX_EXPANDED_NODES = 262_144;
    /** Directed, multi-target A* must not flood an entire air-filled cave when
     * every queued vein has become inaccessible. Retry these targets later. */
    private static final int MAX_QUEUED_NODES = 32_768;
    private static final int EMPTY_QUEUE_WAIT_TICKS = 160;
    private static final int NODES_PER_TICK = 1_024;

    public BlockIndex blockFound;
    public LinkedList<BlockIndex> path;

    private IBlockFilter filter;
    private double maxDistanceToEnd;
    private IZone zone;
    private final ArrayDeque<BlockPos> open = new ArrayDeque<>();
    private record RouteNode(BlockPos position, int distance, int priority) { }
    private final PriorityQueue<RouteNode> directedOpen = new PriorityQueue<>(
            Comparator.comparingInt(RouteNode::priority)
                    .thenComparing(Comparator.comparingInt(RouteNode::distance).reversed()));
    private final Map<BlockPos, Integer> pathDistances = new HashMap<>();
    private final Map<BlockPos, BlockPos> previous = new HashMap<>();
    /** Avoid running expensive ore/tool/Gate filters for the same solid block six times. */
    private final Set<BlockPos> checkedNonAir = new HashSet<>();
    private BlockPos start;
    private int radiusSquared;
    private int expanded;
    private int waitingTicks;
    /** Ticks delayed by the shared server budget (diagnostic, not a failure). */
    private int throttledTicks;
    /** Waiting on a different miner's confirmed explored A* region. */
    private boolean sharedRoutePaused;
    private int sharedRouteWaitTicks;
    private boolean initialized;
    /** Optional stage-3 planned targets. Normal miner BFS is unchanged when empty. */
    private Set<BlockPos> queuedTargets;
    private RobotTargetDistanceIndex targetDistances;
    /** A miner may start searching before its panel has finished discovering work. */
    private boolean watchProcessorQueue;
    private List<BlockPos> adoptedQueueTargets = List.of();

    public AIRobotSearchNearestBlock(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotSearchNearestBlock(EntityRobotBase robot, IBlockFilter filter, double maxDistanceToEnd) {
        this(robot);
        this.filter = filter;
        this.maxDistanceToEnd = maxDistanceToEnd;
        this.zone = robot.getZoneToWork();
    }

    public AIRobotSearchNearestBlock(EntityRobotBase robot, IBlockFilter filter, double maxDistanceToEnd,
            List<BlockPos> targets) {
        this(robot, filter, maxDistanceToEnd);
        // This overload is used only by the miner's first (queue-aware) search.
        // Even an empty initial list must be watched: the server fills its index
        // asynchronously and the robot must not need a logout to notice it.
        watchProcessorQueue = true;
        if (targets != null && !targets.isEmpty()) {
            queuedTargets = new HashSet<>(targets);
            adoptedQueueTargets = List.copyOf(targets);
        }
    }

    @Override
    public void update() {
        // Do not burn A* nodes on a prepared trip which cannot leave enough
        // power for one useful action and the return to a known charger.
        if (watchProcessorQueue && RobotProcessorWorkScheduler.shouldRechargeBeforeWork(robot)) return;
        if (filter == null) {
            fail();
            return;
        }
        // The player may have moved/teleported the robot while a multi-tick
        // search was in progress. Never apply a route from the old start position.
        if (!initialized || !robot.blockPosition().equals(start)) {
            resetSearch();
        }
        // Stage 3/5 race: a miner often enters native BFS before the scheduler
        // has inspected the first nearby section. Previously it stayed in that
        // search until completion even after the queue was populated; rejoining
        // recreated the AI and made the very same queue work immediately.
        // Adopt newly available orders once; a failed queued search reports
        // those targets to the processor for delayed retry.
        if (watchProcessorQueue && queuedTargets == null
                && (expanded == 0 || (expanded & 1023) == 0 || open.isEmpty())) {
            List<BlockPos> incoming = RobotProcessorWorkScheduler.queuedMiningTargets(robot);
            if (!incoming.isEmpty()) {
                queuedTargets = new HashSet<>(incoming);
                adoptedQueueTargets = List.copyOf(incoming);
                if (RobotTrace.enabled()) {
                    RobotTrace.event(robot, "NEAREST_WORK_QUEUE_ADOPTED",
                            "targets=%d searchedNodes=%d", incoming.size(), expanded);
                }
                resetSearch();
            }
        }

        // A managed miner must not run an unrestricted 262k-node BFS every
        // time its panel is between assignments. The panel keeps discovering
        // work independently and the sleeping AI can wake for new candidates.
        if (watchProcessorQueue && queuedTargets == null
                && RobotProcessorWorkScheduler.isManagedMiner(robot)) {
            if (++waitingTicks >= EMPTY_QUEUE_WAIT_TICKS) fail();
            return;
        }

        Level level = world();
        // Another worker can finish a vein or the panel can promote newly
        // discovered close ore while this search is paused. Refresh the
        // snapshot occasionally, rather than waiting for the old failures
        // to expire before noticing a new, untested goal.
        if (sharedRoutePaused && sharedRouteWaitTicks > 0
                && sharedRouteWaitTicks % 20 == 0 && watchProcessorQueue) {
            List<BlockPos> latest = RobotProcessorWorkScheduler.queuedMiningTargets(robot);
            Set<BlockPos> newer = new HashSet<>(latest);
            if (!newer.equals(queuedTargets)) {
                queuedTargets = newer.isEmpty() ? null : newer;
                adoptedQueueTargets = List.copyOf(latest);
                resetSearch();
            }
        }
        // A just-failed A* may have inspected the same corridor and queued
        // ore for a second miner. Avoid repeating the exact expensive search
        // while the hint is fresh. This does not discard the second robot's
        // assignment, and the normal A* resumes on expiry / a world update.
        // Never stop a search already in progress or an autonomous robot.
        if (expanded == 0 && queuedTargets != null && watchProcessorQueue) {
            long retryAt = RobotProcessorWorkScheduler.sharedRouteDeferUntil(robot,
                    start, List.copyOf(queuedTargets));
            if (retryAt > level.getGameTime()) {
                if (!sharedRoutePaused && RobotTrace.enabled()) {
                    RobotTrace.event(robot, "PROCESSOR_ROUTE_CACHE_HIT",
                            "start=%s targets=%d retryIn=%d", start,
                            queuedTargets.size(), retryAt - level.getGameTime());
                }
                sharedRoutePaused = true;
                sharedRouteWaitTicks++;
                return;
            }
        }
        sharedRoutePaused = false;
        // Autonomous miners keep their original per-robot budget. A loaded
        // controller shares one bounded server-tick envelope across its miners.
        int budget = queuedTargets == null ? NODES_PER_TICK
                : RobotProcessorWorkScheduler.requestRouteSearchBudget(robot, NODES_PER_TICK);
        if (budget <= 0) {
            throttledTicks++;
            return;
        }
        int limit = queuedTargets != null ? MAX_QUEUED_NODES : MAX_EXPANDED_NODES;
        while (budget-- > 0 && hasFrontier() && expanded < limit) {
            RouteNode node = queuedTargets == null ? null : directedOpen.remove();
            BlockPos here = node == null ? open.removeFirst() : node.position();
            if (node != null && node.distance() != pathDistances.getOrDefault(here, Integer.MAX_VALUE)) {
                continue; // superseded A* entry, not a visited position
            }
            expanded++;

            for (Direction direction : Direction.values()) {
                BlockPos next = here.relative(direction);
                if (!insideSearchBounds(level, next) || !level.isLoaded(next)) continue;
                BlockState state = level.getBlockState(next);
                if (!state.isAir() && checkedNonAir.add(next)
                        && (zone == null || zone.contains(center(next)))
                        && (queuedTargets == null || queuedTargets.contains(next))
                        && filter.matches(level, next)
                        && (robot.getRegistry() == null
                                || !robot.getRegistry().isTaken(new ResourceIdBlock(next)))) {
                    blockFound = new BlockIndex(next);
                    path = reconstruct(here);
                    if (RobotTrace.enabled()) {
                        RobotTrace.event(robot, "NEAREST_WORK_TARGET",
                                "target=%s pathLength=%d expanded=%d", next, path.size(), expanded);
                    }
                    terminate();
                    return;
                }

                if (!isPassable(level, next, state)) continue;
                if (node == null) {
                    if (!previous.containsKey(next)) {
                        previous.put(next, here);
                        open.addLast(next);
                    }
                } else {
                    int distance = node.distance() + 1;
                    if (distance < pathDistances.getOrDefault(next, Integer.MAX_VALUE)) {
                        pathDistances.put(next, distance);
                        previous.put(next, here);
                        directedOpen.add(new RouteNode(next, distance,
                                distance + targetHeuristic(next)));
                    }
                }
            }
        }

        if (!hasFrontier() || expanded >= limit) {
            if (watchProcessorQueue && queuedTargets == null
                    && RobotProcessorWorkScheduler.isMiningDiscoveryInProgress(robot)) return;
            // Record ONLY goals whose current tool/Gate/zone predicate really
            // allows mining. A failed filter or somebody else's registry claim
            // must not become a shared "blocked route" hint.
            if (watchProcessorQueue && queuedTargets != null && !queuedTargets.isEmpty()) {
                List<BlockPos> actualTargets = new ArrayList<>();
                for (BlockPos target : queuedTargets) {
                    if (!level.isLoaded(target) || level.getBlockState(target).isAir()
                            || zone != null && !zone.contains(center(target))
                            || !filter.matches(level, target)
                            || robot.getRegistry() != null && robot.getRegistry().isTaken(
                                    new ResourceIdBlock(target))) continue;
                    actualTargets.add(target);
                }
                RobotProcessorWorkScheduler.rememberFailedRoute(robot, start, actualTargets,
                        pathDistances.keySet(), !hasFrontier(), expanded);
            }
            if (RobotTrace.enabled()) {
                RobotTrace.event(robot, "NEAREST_WORK_NO_TARGET",
                        "expanded=%d exhausted=%s directed=%s targets=%d targetY=%s radius=%d budgetWaitTicks=%d sharedWaitTicks=%d",
                        expanded, !hasFrontier(), queuedTargets != null,
                        queuedTargets == null ? 0 : queuedTargets.size(), queuedTargetYRange(),
                        radiusSquared == 0 ? 0
                                : (int) Math.sqrt(radiusSquared), throttledTicks, sharedRouteWaitTicks);
            }
            fail();
        }
    }

    /** For diagnosing lower ores that were queued but failed route discovery. */
    private String queuedTargetYRange() {
        if (queuedTargets == null || queuedTargets.isEmpty()) return "none";
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockPos pos : queuedTargets) {
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
        }
        return minY + ".." + maxY;
    }

    private boolean hasFrontier() {
        return queuedTargets == null ? !open.isEmpty() : !directedOpen.isEmpty();
    }

    /** Admissible distance to a position adjacent to any candidate ore. A*
     * therefore still chooses the shortest navigable path, not the nearest
     * Euclidean ore, while it can avoid exploring unrelated air volumes. */
    private int targetHeuristic(BlockPos position) {
        return targetDistances == null ? 0 : targetDistances.distanceToAdjacent(position);
    }

    /** True if this search is or was restricted to actual panel queue entries. */
    public boolean usedProcessorQueue() { return !adoptedQueueTargets.isEmpty(); }

    public List<BlockPos> processorQueueTargets() { return adoptedQueueTargets; }

    private void resetSearch() {
        initialized = true;
        start = robot.blockPosition().immutable();
        int radius = DEFAULT_SEARCH_RADIUS;
        if (maxDistanceToEnd > 0.0D) {
            radius = Math.max(radius, (int) Math.ceil(maxDistanceToEnd) + 8);
        }
        // A controller can assign ore in an outer work-zone chunk more than
        // 96 blocks from a miner standing at the opposite edge. Extend the
        // directed search only as far as its actual targets (plus detours).
        // Autonomous miners retain their original radius and search behavior.
        if (queuedTargets != null) {
            long furthestSquared = 0;
            for (BlockPos target : queuedTargets) {
                long dx = (long) target.getX() - start.getX();
                long dy = (long) target.getY() - start.getY();
                long dz = (long) target.getZ() - start.getZ();
                furthestSquared = Math.max(furthestSquared, dx * dx + dy * dy + dz * dz);
            }
            radius = Math.max(radius, Math.min(256, (int) Math.ceil(Math.sqrt(furthestSquared)) + 24));
        }
        // Prevent integer overflow on pathological custom distance arguments.
        radius = Math.min(radius, 30_000);
        radiusSquared = radius * radius;
        open.clear();
        directedOpen.clear();
        pathDistances.clear();
        previous.clear();
        checkedNonAir.clear();
        targetDistances = queuedTargets == null ? null : new RobotTargetDistanceIndex(queuedTargets);
        expanded = 0;
        waitingTicks = 0;
        throttledTicks = 0;
        sharedRoutePaused = false;
        sharedRouteWaitTicks = 0;
        previous.put(start, null);
        if (queuedTargets == null) {
            open.addLast(start);
        } else {
            pathDistances.put(start, 0);
            directedOpen.add(new RouteNode(start, 0, targetHeuristic(start)));
        }
        blockFound = null;
        path = null;
        if (RobotTrace.enabled()) {
            RobotTrace.event(robot, "NEAREST_WORK_SEARCH_START",
                    "start=%s radius=%d budgetPerTick=%d", start, radius, NODES_PER_TICK);
        }
    }

    private boolean insideSearchBounds(Level level, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        long dx = (long) pos.getX() - start.getX();
        long dy = (long) pos.getY() - start.getY();
        long dz = (long) pos.getZ() - start.getZ();
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }

    private LinkedList<BlockIndex> reconstruct(BlockPos destination) {
        LinkedList<BlockIndex> result = new LinkedList<>();
        BlockPos current = destination;
        while (current != null && !current.equals(start)) {
            result.addFirst(new BlockIndex(current));
            current = previous.get(current);
        }
        return result;
    }

    private static boolean isPassable(Level level, BlockPos pos, BlockState state) {
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private Level world() {
        //? if <1.20 {
        return robot.level;
        //?} else {
        /*?
        return robot.level();
        ?*/
        //?}
    }

    private void fail() {
        setSuccess(false);
        terminate();
    }

    /** The owning AIRobotSearchAndGotoBlock performs the final atomic reservation. */
    public boolean takeResource() {
        return blockFound != null && robot.getRegistry() != null
                && robot.getRegistry().take(new ResourceIdBlock(blockFound), robot);
    }

    @Override
    public int getEnergyCost() {
        return sharedRoutePaused || watchProcessorQueue
                && (queuedTargets == null && RobotProcessorWorkScheduler.isManagedMiner(robot)
                        || RobotProcessorWorkScheduler.shouldRechargeBeforeWork(robot)) ? 0 : 2;
    }

    @Override
    public boolean canLoadFromNBT() {
        // The ore predicate is a runtime lambda capturing the current board.
        // A save recreates the search from that board instead of restoring a stale closure.
        return false;
    }
}
