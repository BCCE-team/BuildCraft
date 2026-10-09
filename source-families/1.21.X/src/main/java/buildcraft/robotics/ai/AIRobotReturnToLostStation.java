package buildcraft.robotics.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Emergency AI used when a robot loses its main docking station.
 *
 * <p>The robot keeps the last known station coordinates, returns to a safe air block within five blocks of them and
 * waits there. It periodically retries the station so temporary chunk unloads or station re-registration do not leave
 * the robot permanently stranded at its last work target.</p>
 */
public class AIRobotReturnToLostStation extends AIRobot {
    private static final int RETURN_RADIUS = 5;
    private static final int STATION_RETRY_TICKS = 20;
    private static final int PATH_RETRY_TICKS = 40;

    private BlockIndex stationIndex;
    @Nullable
    private Direction stationSide;

    private final List<BlockIndex> parkingCandidates = new ArrayList<>();
    private int nextCandidate;
    private int stationRetry;
    private int pathRetry;
    private boolean parked;
    private boolean initialized;
    private boolean finishPending;
    private boolean allowStationRebind = true;

    public AIRobotReturnToLostStation(EntityRobotBase robot) {
        super(robot);
        setSuccess(false);
    }

    public AIRobotReturnToLostStation(EntityRobotBase robot, BlockIndex stationIndex, @Nullable Direction stationSide) {
        this(robot, stationIndex, stationSide, true);
    }

    public AIRobotReturnToLostStation(EntityRobotBase robot, BlockIndex stationIndex, @Nullable Direction stationSide,
            boolean allowStationRebind) {
        this(robot);
        this.stationIndex = copy(stationIndex);
        this.stationSide = stationSide;
        this.allowStationRebind = allowStationRebind;
    }

    @Override
    public void start() {
        initialize();
        if (!allowStationRebind || !tryStartDocking()) {
            rebuildParkingCandidates();
            startNextParkingPath();
        }
    }

    @Override
    public void preempt(AIRobot ai) {
        initialize();
        if (finishPending) {
            return;
        }

        if (allowStationRebind) {
            if (stationRetry > 0) {
                stationRetry--;
            } else {
                stationRetry = STATION_RETRY_TICKS;
                // Do not restart an in-progress docking route every second. Parking routes may be interrupted as soon
                // as the station reappears, but AIRobotGotoStation must be allowed to finish its own path.
                if (!(ai instanceof AIRobotGotoStation) && tryStartDocking()) {
                    return;
                }
            }
        }

        if (ai == null && !parked) {
            if (pathRetry > 0) {
                pathRetry--;
            } else {
                rebuildParkingCandidates();
                startNextParkingPath();
            }
        }
    }

    private void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        robot.releaseResources();
        robot.undock();
        robot.setDeltaMovement(Vec3.ZERO);
    }

    @Override
    public void update() {
        robot.setDeltaMovement(Vec3.ZERO);
        if (finishPending) {
            terminate();
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStation) {
            if (ai.success()) {
                setSuccess(true);
                terminate();
            } else {
                parked = false;
                pathRetry = 0;
                rebuildParkingCandidates();
                startNextParkingPath();
            }
            return;
        }

        if (ai instanceof AIRobotGotoBlock) {
            if (ai.success() && isInsideReturnRadius(robot.blockPosition())) {
                parked = true;
                robot.setDeltaMovement(Vec3.ZERO);
                stationRetry = 0;
            } else {
                startNextParkingPath();
            }
        }
    }

    private boolean tryStartDocking() {
        DockingStation station = getStation();
        if (station == null) {
            return false;
        }

        long takingId = station.robotIdTaking();
        if (takingId != EntityRobotBase.NULL_ROBOT_ID && takingId != robot.getRobotId()) {
            return false;
        }
        if (!station.takeAsMain(robot)) {
            return false;
        }

        if (station == robot.getDockingStation()) {
            setSuccess(true);
            finishPending = true;
        } else {
            parked = false;
            startDelegateAI(new AIRobotGotoStation(robot, station));
        }
        return true;
    }

    @Nullable
    private DockingStation getStation() {
        IRobotRegistry registry = robot.getRegistry();
        if (registry == null || stationIndex == null) {
            return null;
        }
        return registry.getStation(stationIndex.toBlockPos(), stationSide);
    }

    private void rebuildParkingCandidates() {
        parkingCandidates.clear();
        nextCandidate = 0;

        if (stationIndex == null) {
            parked = true;
            return;
        }

        BlockPos stationPos = stationIndex.toBlockPos();
        BlockPos preferred = stationSide == null ? stationPos.above() : stationPos.relative(stationSide);
        Level level = robot.level();
        Set<BlockPos> added = new HashSet<>();

        addCandidate(level, stationPos, preferred, added);

        List<BlockPos> positions = new ArrayList<>();
        for (int dx = -RETURN_RADIUS; dx <= RETURN_RADIUS; dx++) {
            for (int dy = -RETURN_RADIUS; dy <= RETURN_RADIUS; dy++) {
                for (int dz = -RETURN_RADIUS; dz <= RETURN_RADIUS; dz++) {
                    if (dx * dx + dy * dy + dz * dz > RETURN_RADIUS * RETURN_RADIUS) {
                        continue;
                    }
                    BlockPos pos = stationPos.offset(dx, dy, dz);
                    if (!pos.equals(stationPos) && !pos.equals(preferred)) {
                        positions.add(pos);
                    }
                }
            }
        }

        positions.sort(Comparator
                .comparingInt((BlockPos pos) -> squaredDistance(pos, preferred))
                .thenComparingDouble(pos -> robot.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))
                .thenComparingInt(BlockPos::getY)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));

        for (BlockPos pos : positions) {
            addCandidate(level, stationPos, pos, added);
        }

        if (parkingCandidates.isEmpty()) {
            parked = isInsideReturnRadius(robot.blockPosition());
            pathRetry = PATH_RETRY_TICKS;
        }
    }

    private void addCandidate(Level level, BlockPos stationPos, BlockPos pos, Set<BlockPos> added) {
        if (!added.add(pos) || !isSafeParkingSpot(level, stationPos, pos)) {
            return;
        }
        parkingCandidates.add(new BlockIndex(pos));
    }

    private boolean isSafeParkingSpot(Level level, BlockPos stationPos, BlockPos pos) {
        if (pos.equals(stationPos) || !isInsideReturnRadius(pos) || !level.isLoaded(pos)) {
            return false;
        }
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }

        BlockState state = level.getBlockState(pos);
        if (!state.isAir()) {
            return false;
        }

        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        AABB box = new AABB(x - 0.25D, y - 0.25D, z - 0.25D, x + 0.25D, y + 0.25D, z + 0.25D);
        return level.noCollision(robot, box);
    }

    private void startNextParkingPath() {
        if (getDelegateAI() != null) {
            return;
        }

        while (nextCandidate < parkingCandidates.size()) {
            BlockIndex target = parkingCandidates.get(nextCandidate++);
            if (robot.blockPosition().equals(target.toBlockPos())) {
                parked = true;
                robot.setDeltaMovement(Vec3.ZERO);
                return;
            }

            double distance = Math.sqrt(robot.distanceToSqr(target.x + 0.5D, target.y + 0.5D, target.z + 0.5D));
            startDelegateAI(new AIRobotGotoBlock(robot, target.x, target.y, target.z, Math.max(32.0D, distance + 16.0D)));
            return;
        }

        parked = isInsideReturnRadius(robot.blockPosition());
        robot.setDeltaMovement(Vec3.ZERO);
        pathRetry = PATH_RETRY_TICKS;
    }

    private boolean isInsideReturnRadius(BlockPos pos) {
        if (stationIndex == null) {
            return false;
        }
        return squaredDistance(pos, stationIndex.toBlockPos()) <= RETURN_RADIUS * RETURN_RADIUS;
    }

    private static int squaredDistance(BlockPos first, BlockPos second) {
        int dx = first.getX() - second.getX();
        int dy = first.getY() - second.getY();
        int dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static BlockIndex copy(BlockIndex index) {
        return index == null ? null : new BlockIndex(index.x, index.y, index.z);
    }

    @Override
    public int getEnergyCost() {
        // Losing a station is a recovery condition. A completely discharged robot must still be recoverable.
        return 0;
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        if (stationIndex != null) {
            CompoundTag indexTag = new CompoundTag();
            stationIndex.writeTo(indexTag);
            nbt.put("stationIndex", indexTag);
            nbt.putByte("stationSide", (byte) (stationSide == null ? -1 : stationSide.ordinal()));
        }
        nbt.putBoolean("parked", parked);
        nbt.putBoolean("allowStationRebind", allowStationRebind);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("stationIndex")) {
            stationIndex = new BlockIndex(nbt.getCompound("stationIndex"));
            int sideId = nbt.getByte("stationSide");
            stationSide = sideId >= 0 && sideId < Direction.values().length ? Direction.values()[sideId] : null;
        }
        parked = nbt.getBoolean("parked");
        allowStationRebind = !nbt.contains("allowStationRebind") || nbt.getBoolean("allowStationRebind");
        initialized = false;
        finishPending = false;
        stationRetry = 0;
        pathRetry = 0;
    }
}
