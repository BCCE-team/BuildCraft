package buildcraft.robotics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Picker-level pathing that travels through soft or non-colliding blocks. */
public class AIRobotGotoBlock extends AIRobotGoto {
    private LinkedList<BlockIndex> path;
    private double prevDistance = Double.MAX_VALUE;
    private int finalX, finalY, finalZ;
    private double maxDistance;
    private BlockIndex lastBlockInPath;
    private boolean loadedFromNBT;
    private int stuckTicks;
    private int repathAttempts;
    private static final int MAX_REPATH_ATTEMPTS = 3;

    // Only station/charging returns opt in. Work-target path quotas are untouched.
    private static final int RETURN_NODES_PER_TICK = 1024;
    private static final int RETURN_CHUNK_RETRY_TICKS = 40;
    private boolean stationReturn;
    private int returnStationX, returnStationY, returnStationZ;
    private RobotStationPathPlanner stationPlanner;

    public AIRobotGotoBlock(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotGotoBlock(EntityRobotBase robot, int x, int y, int z) {
        this(robot, x, y, z, 0);
    }

    public AIRobotGotoBlock(EntityRobotBase robot, int x, int y, int z, double maxDistance) {
        super(robot);
        this.finalX = x;
        this.finalY = y;
        this.finalZ = z;
        this.maxDistance = maxDistance;
    }

    public AIRobotGotoBlock(EntityRobotBase robot, LinkedList<BlockIndex> path) {
        super(robot);
        this.path = path;
        if (!path.isEmpty()) {
            BlockIndex last = path.getLast();
            finalX = last.x;
            finalY = last.y;
            finalZ = last.z;
            setNextInPath();
        }
    }

    /**
     * Search the actually passable terrain for a route to any open face of the
     * docking pipe. Unlike work searches this one can search winding paths over
     * multiple ticks, without a small straight-line radius cutoff.
     */
    public AIRobotGotoBlock forStationReturn(int stationX, int stationY, int stationZ) {
        stationReturn = true;
        returnStationX = stationX;
        returnStationY = stationY;
        returnStationZ = stationZ;
        return this;
    }

    @Override
    public void start() {
        robot.undock();
    }

    @Override
    public int getEnergyCost() {
        // Deliberation is not travel: no battery cost before a route is ready.
        return stationReturn && path == null ? 0 : super.getEnergyCost();
    }

    @Override
    public void update() {
        if (loadedFromNBT) {
            setNextInPath();
            loadedFromNBT = false;
        }
        if (path == null) {
            long now = robot.level().getGameTime();
            if (now < nextRepathTick) return;
            if (stationReturn) {
                if (stationPlanner == null) {
                    stationPlanner = new RobotStationPathPlanner(robot.level(), robot.blockPosition(),
                        new BlockPos(returnStationX, returnStationY, returnStationZ),
                        new BlockPos(finalX, finalY, finalZ));
                    if (RobotTrace.enabled()) RobotTrace.event(robot, "RETURN_PATH_START",
                        "station=(%d,%d,%d) start=%s", returnStationX, returnStationY,
                        returnStationZ, robot.blockPosition());
                }
                RobotStationPathPlanner.Result result = stationPlanner.advance(robot.level(), RETURN_NODES_PER_TICK);
                if (result == RobotStationPathPlanner.Result.SEARCHING) return;
                if (result == RobotStationPathPlanner.Result.WAITING_FOR_CHUNKS) {
                    if (RobotTrace.enabled()) RobotTrace.event(robot, "RETURN_PATH_WAIT_CHUNKS",
                        "visited=%d retryIn=%d", stationPlanner.exploredNodes(), RETURN_CHUNK_RETRY_TICKS);
                    stationPlanner = null;
                    nextRepathTick = now + RETURN_CHUNK_RETRY_TICKS;
                    return;
                }
                if (result == RobotStationPathPlanner.Result.NO_ROUTE) {
                    if (RobotTrace.enabled()) RobotTrace.event(robot, "RETURN_PATH_NO_ROUTE",
                        "visited=%d station=(%d,%d,%d)", stationPlanner.exploredNodes(),
                        returnStationX, returnStationY, returnStationZ);
                    stationPlanner = null;
                    setSuccess(false);
                    terminate();
                    return;
                }
                path = stationPlanner.path();
                if (RobotTrace.enabled()) RobotTrace.event(robot, "RETURN_PATH_FOUND",
                    "visited=%d waypoints=%d", stationPlanner.exploredNodes(), path.size());
                stationPlanner = null;
            } else {
                path = findPath();
            }
            if (path == null || path.isEmpty()) {
                setSuccess(false);
                terminate();
                return;
            }
            lastBlockInPath = path.getLast();
            setNextInPath();
        }

        if (path != null && !path.isEmpty()) {
            double distance = distanceTo(nextX, nextY, nextZ);
            boolean notProgressing = distance >= prevDistance - 0.005D;
            // Station returns reach the center of every voxel before turning.
            // The ordinary work route keeps its historic 0.18-block tolerance.
            if (distance <= (stationReturn ? 0.05D : 0.18D)) {
                repathAttempts = 0;
                path.removeFirst();
                setNextInPath();
            } else if (notProgressing && ++stuckTicks > 8) {
                robot.setDeltaMovement(Vec3.ZERO);
                if (++repathAttempts > MAX_REPATH_ATTEMPTS) {
                    setSuccess(false);
                    terminate();
                    return;
                }
                path = null;
                stationPlanner = null;
                nextRepathTick = robot.level().getGameTime() + 20;
                prevDistance = Double.MAX_VALUE;
            } else {
                stuckTicks = notProgressing ? stuckTicks : 0;
                prevDistance = Math.min(prevDistance, distance);
                if (stationReturn) {
                    // Blocks can change AFTER the waypoint was planned. Do not
                    // keep flying into a newly placed solid block.
                    BlockPos waypoint = BlockPos.containing(nextX, nextY, nextZ);
                    if (!waypoint.equals(robot.blockPosition()) && !isSoft(robot.level(), waypoint)) {
                        path = null;
                        stationPlanner = null;
                        robot.setDeltaMovement(Vec3.ZERO);
                        nextRepathTick = robot.level().getGameTime() + 20;
                        return;
                    }
                    // A tight turn at a cave corner must not drift through rock.
                    setDestination(robot, nextX, nextY, nextZ);
                    Vec3 motion = robot.getDeltaMovement();
                    double velocity = motion.length();
                    if (velocity > distance && velocity > 0.0D) {
                        robot.setDeltaMovement(motion.scale(distance / velocity));
                    }
                }
            }
        }

        if (path != null && path.isEmpty()) {
            robot.setDeltaMovement(Vec3.ZERO);
            terminate();
        }
    }

    private double distanceTo(double x, double y, double z) {
        double dx = robot.getX() - x;
        double dy = robot.getY() - y;
        double dz = robot.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void setNextInPath() {
        if (path != null && !path.isEmpty()) {
            BlockIndex next = path.getFirst();
            prevDistance = Double.MAX_VALUE;
            stuckTicks = 0;

            // The robot may START inside a pipe, but must not fly through a newly
            // blocked waypoint. The old isFirst check also accepted hard blocks
            // that appeared midway through an existing route.
            if (next.toBlockPos().equals(robot.blockPosition()) || isSoft(robot.level(), next.toBlockPos())) {
                setDestination(robot, next.x + 0.5D, next.y + 0.5D, next.z + 0.5D);
                robot.aimItemAt(next.x, next.y, next.z);
            } else {
                path = null;
                stationPlanner = null;
                robot.setDeltaMovement(Vec3.ZERO);
            }
        }
    }

    private long nextRepathTick;

    private LinkedList<BlockIndex> findPath() {
        BlockPos start = robot.blockPosition();
        BlockPos target = new BlockPos(finalX, finalY, finalZ);
        if (start.equals(target)) {
            LinkedList<BlockIndex> single = new LinkedList<>();
            single.add(new BlockIndex(target));
            return single;
        }

        Level level = robot.level();
        if (!isSoft(level, target)) return null;

        LinkedList<BlockIndex> direct = directFallback(start, target, level);
        if (direct != null) return direct;
        return findAStarPath(start, target, level);
    }

    private LinkedList<BlockIndex> findAStarPath(BlockPos start, BlockPos target, Level level) {
        int hardLimit = maxDistance > 0 ? Math.max(1, (int) Math.ceil(maxDistance)) : 256;
        int maxVisited = (int) Math.min(8192L, Math.max(512L, (long) hardLimit * 8L));
        PriorityQueue<PathNode> open = new PriorityQueue<>((a, b) -> {
            int score = Double.compare(a.score(), b.score());
            return score != 0 ? score : Long.compare(a.sequence(), b.sequence());
        });
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Map<BlockPos, Integer> bestCost = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        long sequence = 0;

        bestCost.put(start, 0);
        open.add(new PathNode(start, heuristic(start, target), sequence++));

        while (!open.isEmpty() && closed.size() < maxVisited) {
            PathNode node = open.poll();
            BlockPos current = node.pos();
            if (!closed.add(current)) continue;
            if (current.equals(target)) return reconstruct(start, target, parent);
            int currentCost = bestCost.getOrDefault(current, Integer.MAX_VALUE);
            for (Direction dir : Direction.values()) {
                BlockPos next = current.relative(dir);
                if (closed.contains(next)) continue;
                if (distanceSqr(next, start) > (long) hardLimit * hardLimit) continue;
                if (!isSoft(level, next)) continue;
                int newCost = currentCost + 1;
                if (newCost < bestCost.getOrDefault(next, Integer.MAX_VALUE)) {
                    bestCost.put(next, newCost);
                    parent.put(next, current);
                    open.add(new PathNode(next, newCost + heuristic(next, target), sequence++));
                }
            }
        }
        return null;
    }

    private static double distanceSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isSoft(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.getCollisionShape(level, pos).isEmpty();
    }

    private static LinkedList<BlockIndex> reconstruct(BlockPos start, BlockPos target, Map<BlockPos, BlockPos> parent) {
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos cur = target;
        while (cur != null && !cur.equals(start)) {
            reversed.add(cur);
            cur = parent.get(cur);
        }
        Collections.reverse(reversed);
        LinkedList<BlockIndex> out = new LinkedList<>();
        for (BlockPos pos : reversed) out.add(new BlockIndex(pos));
        return out;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }

    private static LinkedList<BlockIndex> directFallback(BlockPos start, BlockPos target, Level level) {
        int[][] orders = {
                {0, 1, 2}, {0, 2, 1},
                {1, 0, 2}, {1, 2, 0},
                {2, 0, 1}, {2, 1, 0}
        };
        for (int[] order : orders) {
            LinkedList<BlockIndex> out = traceAxisPath(start, target, level, order);
            if (out != null) return out;
        }
        return null;
    }

    private static LinkedList<BlockIndex> traceAxisPath(BlockPos start, BlockPos target, Level level, int[] order) {
        LinkedList<BlockIndex> out = new LinkedList<>();
        int[] current = {start.getX(), start.getY(), start.getZ()};
        int[] end = {target.getX(), target.getY(), target.getZ()};

        for (int axis : order) {
            while (current[axis] != end[axis]) {
                current[axis] += Integer.compare(end[axis], current[axis]);
                BlockPos pos = new BlockPos(current[0], current[1], current[2]);
                if (!isSoft(level, pos)) return null;
                out.add(new BlockIndex(pos));
            }
        }
        return out;
    }

    private record PathNode(BlockPos pos, double score, long sequence) { }

    @Override
    public void end() {
        robot.setDeltaMovement(Vec3.ZERO);
        stationPlanner = null;
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        nbt.putInt("finalX", finalX);
        nbt.putInt("finalY", finalY);
        nbt.putInt("finalZ", finalZ);
        nbt.putDouble("maxDistance", maxDistance);
        nbt.putBoolean("stationReturn", stationReturn);
        if (stationReturn) {
            nbt.putInt("returnStationX", returnStationX);
            nbt.putInt("returnStationY", returnStationY);
            nbt.putInt("returnStationZ", returnStationZ);
        }
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        finalX = nbt.getInt("finalX");
        finalY = nbt.getInt("finalY");
        finalZ = nbt.getInt("finalZ");
        maxDistance = nbt.getDouble("maxDistance");
        stationReturn = nbt.getBoolean("stationReturn");
        if (stationReturn) {
            returnStationX = nbt.getInt("returnStationX");
            returnStationY = nbt.getInt("returnStationY");
            returnStationZ = nbt.getInt("returnStationZ");
        }
        stationPlanner = null; // Rebuild the transient search after a reload.
        loadedFromNBT = true;
    }
}
