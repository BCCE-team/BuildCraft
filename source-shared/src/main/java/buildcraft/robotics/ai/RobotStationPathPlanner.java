package buildcraft.robotics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import buildcraft.lib.internal.core.BlockIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Incremental station-only A* search. A worker can follow a long, winding mine
 * entrance back to its dock without a short distance sphere or a single-tick
 * 8192-node cutoff. The regular work-target search is unchanged.
 *
 * Only the physical docking face is an acceptable goal. A free cell on the
 * opposite side of the pipe is NOT a valid substitute: the final straight
 * docking movement would pass through the pipe or a solid wall to reach the
 * station's actual socket. This planner NEVER crosses a colliding block.
 */
public final class RobotStationPathPlanner {
    public enum Result { SEARCHING, FOUND, NO_ROUTE, WAITING_FOR_CHUNKS }

    private static final int MAX_TOTAL_VISITED = 262_144;
    private static final double HEURISTIC_WEIGHT = 1.25D;
    private static final int[][] AXIS_ORDERS = {
        {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
        {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
    };

    private final BlockPos origin;
    private final List<BlockPos> goals;
    private final Set<BlockPos> goalSet;
    private final PriorityQueue<Node> open = new PriorityQueue<>(
        Comparator.comparingDouble(Node::priority)
            .thenComparingInt(Node::distanceToGoal)
            .thenComparingLong(Node::order));
    private final Map<BlockPos, Integer> costs = new HashMap<>();
    private final Map<BlockPos, BlockPos> parents = new HashMap<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private LinkedList<BlockIndex> completedPath;
    private Result result = Result.SEARCHING;
    private long sequence;
    private boolean sawUnloadedChunk;

    public RobotStationPathPlanner(Level level, BlockPos origin, BlockPos station, BlockPos preferredApproach) {
        this.origin = origin.immutable();
        // The socket has a fixed orientation. Choosing a different face might
        // find a path, but the following final move would cut straight through
        // the station or surrounding stone. Only target the configured socket.
        BlockPos face = preferredApproach == null ? station.above() : preferredApproach;
        sawUnloadedChunk = !level.isLoaded(face);
        if (sawUnloadedChunk || !isOpen(level, face)) {
            goals = List.of();
            goalSet = Set.of();
            result = sawUnloadedChunk ? Result.WAITING_FOR_CHUNKS : Result.NO_ROUTE;
            return;
        }
        goals = List.of(face.immutable());
        goalSet = Set.copyOf(goals);
        // Starting at a free approach cell is an already-completed route.
        if (goalSet.contains(this.origin)) {
            completedPath = new LinkedList<>();
            completedPath.add(new BlockIndex(this.origin));
            result = Result.FOUND;
            return;
        }
        int estimate = heuristic(this.origin);
        costs.put(this.origin, 0);
        open.add(new Node(this.origin, estimate * HEURISTIC_WEIGHT, estimate, sequence++));
    }

    public Result result() {
        return result;
    }

    public int exploredNodes() {
        return visited.size();
    }

    public LinkedList<BlockIndex> path() {
        return completedPath == null ? null : new LinkedList<>(completedPath);
    }

    /**
     * Try very cheap open-air axis routes once, then process at most budget A*
     * expansions; remaining frontier and parent data are kept for next tick.
     */
    public Result advance(Level level, int budget) {
        if (result != Result.SEARCHING) return result;
        if (visited.isEmpty()) {
            for (BlockPos goal : goals) {
                for (int[] order : AXIS_ORDERS) {
                    LinkedList<BlockIndex> direct = traceAxisRoute(level, origin, goal, order);
                    if (direct != null) {
                        completedPath = direct;
                        result = Result.FOUND;
                        return result;
                    }
                }
            }
        }
        for (int expanded = 0; expanded < budget && !open.isEmpty(); ) {
            Node node = open.poll();
            BlockPos at = node.pos();
            if (!visited.add(at)) continue;
            expanded++;
            if (goalSet.contains(at)) {
                completedPath = reconstruct(at);
                result = Result.FOUND;
                return result;
            }
            if (visited.size() >= MAX_TOTAL_VISITED) {
                result = sawUnloadedChunk ? Result.WAITING_FOR_CHUNKS : Result.NO_ROUTE;
                return result;
            }
            int currentCost = costs.getOrDefault(at, Integer.MAX_VALUE);
            for (Direction side : Direction.values()) {
                BlockPos next = at.relative(side);
                if (visited.contains(next)) continue;
                if (!level.isLoaded(next)) {
                    sawUnloadedChunk = true;
                    continue;
                }
                if (!isOpen(level, next)) continue;
                int candidateCost = currentCost + 1;
                if (candidateCost < costs.getOrDefault(next, Integer.MAX_VALUE)) {
                    costs.put(next, candidateCost);
                    parents.put(next, at);
                    int estimate = heuristic(next);
                    open.add(new Node(next,
                        candidateCost + HEURISTIC_WEIGHT * estimate, estimate, sequence++));
                }
            }
        }
        if (open.isEmpty()) result = sawUnloadedChunk ? Result.WAITING_FOR_CHUNKS : Result.NO_ROUTE;
        return result;
    }

    private int heuristic(BlockPos position) {
        int best = Integer.MAX_VALUE;
        for (BlockPos goal : goals) {
            best = Math.min(best, Math.abs(position.getX() - goal.getX())
                + Math.abs(position.getY() - goal.getY())
                + Math.abs(position.getZ() - goal.getZ()));
        }
        return best;
    }

    private LinkedList<BlockIndex> reconstruct(BlockPos goal) {
        List<BlockPos> reverse = new ArrayList<>();
        BlockPos current = goal;
        while (current != null && !current.equals(origin)) {
            reverse.add(current);
            current = parents.get(current);
        }
        Collections.reverse(reverse);
        LinkedList<BlockIndex> out = new LinkedList<>();
        for (BlockPos pos : reverse) out.add(new BlockIndex(pos));
        return out;
    }

    private static LinkedList<BlockIndex> traceAxisRoute(Level level, BlockPos start,
                                                         BlockPos goal, int[] order) {
        LinkedList<BlockIndex> route = new LinkedList<>();
        int[] point = {start.getX(), start.getY(), start.getZ()};
        int[] end = {goal.getX(), goal.getY(), goal.getZ()};
        // A direct-ray probe is only an optimization. Large routes must be
        // processed incrementally rather than checked millions of cells in one tick.
        int probeLength = Math.abs(point[0] - end[0]) + Math.abs(point[1] - end[1])
            + Math.abs(point[2] - end[2]);
        if (probeLength > 4096) return null;
        for (int axis : order) {
            while (point[axis] != end[axis]) {
                point[axis] += Integer.compare(end[axis], point[axis]);
                BlockPos next = new BlockPos(point[0], point[1], point[2]);
                if (!isOpen(level, next)) return null;
                route.add(new BlockIndex(next));
            }
        }
        return route;
    }

    private static boolean isOpen(Level level, BlockPos position) {
        if (!level.isLoaded(position)) return false; // Never generate unloaded chunks.
        BlockState state = level.getBlockState(position);
        return state.isAir() || state.getCollisionShape(level, position).isEmpty();
    }

    private record Node(BlockPos pos, double priority, int distanceToGoal, long order) { }
}
