package buildcraft.robotics.ai;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.RobotTrace;
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
 * <p>This is a breadth-first search over passable air/non-colliding positions.
 * Every route of length N is examined before a route of length N+1; the first
 * matching block adjacent to a reached position is therefore at minimum path
 * distance. Unlike target-by-target A*, one traversal handles all eligible ores
 * and crosses loaded chunk boundaries without preferring the starting chunk.
 * Work-zone limits apply to ore targets, not to the corridor used to reach them.</p>
 *
 * <p>Like the other robot searches, this does not load chunks or search without
 * bounds. The node budget is spread across game ticks to protect server TPS.</p>
 */
public class AIRobotSearchNearestBlock extends AIRobot {
    private static final int DEFAULT_SEARCH_RADIUS = 96;
    private static final int MAX_EXPANDED_NODES = 262_144;
    private static final int NODES_PER_TICK = 1_024;

    public BlockIndex blockFound;
    public LinkedList<BlockIndex> path;

    private IBlockFilter filter;
    private double maxDistanceToEnd;
    private IZone zone;
    private final ArrayDeque<BlockPos> open = new ArrayDeque<>();
    private final Map<BlockPos, BlockPos> previous = new HashMap<>();
    /** Avoid running expensive ore/tool/Gate filters for the same solid block six times. */
    private final Set<BlockPos> checkedNonAir = new HashSet<>();
    private BlockPos start;
    private int radiusSquared;
    private int expanded;
    private boolean initialized;

    public AIRobotSearchNearestBlock(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotSearchNearestBlock(EntityRobotBase robot, IBlockFilter filter, double maxDistanceToEnd) {
        this(robot);
        this.filter = filter;
        this.maxDistanceToEnd = maxDistanceToEnd;
        this.zone = robot.getZoneToWork();
    }

    @Override
    public void update() {
        if (filter == null) {
            fail();
            return;
        }
        // The player may have moved/teleported the robot while a multi-tick
        // search was in progress. Never apply a route from the old start position.
        if (!initialized || !robot.blockPosition().equals(start)) {
            resetSearch();
        }

        Level level = world();
        int budget = NODES_PER_TICK;
        while (budget-- > 0 && !open.isEmpty() && expanded < MAX_EXPANDED_NODES) {
            BlockPos here = open.removeFirst();
            expanded++;

            for (Direction direction : Direction.values()) {
                BlockPos next = here.relative(direction);
                if (!insideSearchBounds(level, next) || !level.isLoaded(next)) {
                    continue;
                }

                BlockState state = level.getBlockState(next);
                // All ores are non-air blocks. Checking non-air positions only
                // keeps a cavern search from invoking a tool/filter per air voxel.
                if (!state.isAir() && checkedNonAir.add(next)
                        && (zone == null || zone.contains(center(next)))
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

                // The start may itself be occupied by the docking pluggable.
                // It is allowed as a BFS origin, never as a transit shortcut.
                if (!previous.containsKey(next) && isPassable(level, next, state)) {
                    previous.put(next, here);
                    open.addLast(next);
                }
            }
        }

        if (open.isEmpty() || expanded >= MAX_EXPANDED_NODES) {
            if (RobotTrace.enabled()) {
                RobotTrace.event(robot, "NEAREST_WORK_NO_TARGET",
                        "expanded=%d exhausted=%s", expanded, open.isEmpty());
            }
            fail();
        }
    }

    private void resetSearch() {
        initialized = true;
        start = robot.blockPosition().immutable();
        int radius = DEFAULT_SEARCH_RADIUS;
        if (maxDistanceToEnd > 0.0D) {
            radius = Math.max(radius, (int) Math.ceil(maxDistanceToEnd) + 8);
        }
        // Prevent integer overflow on pathological custom distance arguments.
        radius = Math.min(radius, 30_000);
        radiusSquared = radius * radius;
        open.clear();
        previous.clear();
        checkedNonAir.clear();
        expanded = 0;
        open.addLast(start);
        previous.put(start, null);
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
        return 2;
    }

    @Override
    public boolean canLoadFromNBT() {
        // The ore predicate is a runtime lambda capturing the current board.
        // A save recreates the search from that board instead of restoring a stale closure.
        return false;
    }
}
