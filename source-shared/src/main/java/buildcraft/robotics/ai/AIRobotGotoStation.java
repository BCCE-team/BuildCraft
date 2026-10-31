package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.RobotTrace;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

public class AIRobotGotoStation extends AIRobot {
    private BlockIndex stationIndex;
    private Direction stationSide;
    private boolean reservedStation;
    private boolean restoreReservationAfterLoad;
    private boolean waitingForStation;
    private int ticksUntilRetry;
    private int unavailableRetries;
    private static final int RETRY_TICKS = 20;
    private static final int MAX_UNAVAILABLE_RETRIES = 3;

    public AIRobotGotoStation(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotGotoStation(EntityRobotBase robot, DockingStation station) {
        this(robot);
        stationIndex = station.index();
        stationSide = station.side();
        setSuccess(false);
    }

    @Override
    public void start() {
        attemptDocking();
    }

    /** Occupancy is contention, not a permanent routing error. */
    private void attemptDocking() {
        DockingStation station = getStation();
        if (station == robot.getDockingStation() && station != null) {
            setSuccess(true);
            if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_ALREADY_DOCKED", "station=%s", station);
            terminate();
            return;
        }
        if (station != null && station.isInitialized() && station.take(robot)) {
            waitingForStation = false;
            unavailableRetries = 0;
            reservedStation = true;
            if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_RESERVED", "station=%s", station);
            Direction side = station.side();
            int dx = side == null ? 0 : side.getStepX();
            int dy = side == null ? 0 : side.getStepY();
            int dz = side == null ? 0 : side.getStepZ();
            int targetX = station.x() + dx;
            int targetY = station.y() + dy;
            int targetZ = station.z() + dz;
            startDelegateAI(new AIRobotGotoBlock(robot, targetX, targetY, targetZ, pathRangeTo(targetX, targetY, targetZ)));
            return;
        }
        boolean occupied = station != null && station.robotIdTaking() != EntityRobotBase.NULL_ROBOT_ID
                && station.robotIdTaking() != robot.getRobotId();
        if (!occupied && ++unavailableRetries > MAX_UNAVAILABLE_RETRIES) {
            if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_UNAVAILABLE",
                    "station=%s retries=%d", station, unavailableRetries);
            setSuccess(false);
            terminate();
            return;
        }
        waitingForStation = true;
        ticksUntilRetry = RETRY_TICKS;
        robot.setDeltaMovement(Vec3.ZERO);
        if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_WAIT",
                "station=%s occupied=%s retryIn=%d", station, occupied, ticksUntilRetry);
    }

    @Override
    public void update() {
        if (waitingForStation && --ticksUntilRetry <= 0) {
            attemptDocking();
        }
    }

    @Override
    public int getEnergyCost() {
        // Passive queueing should not exhaust a robot while another is docked.
        return waitingForStation ? 0 : 1;
    }

    @Override
    public void preempt(AIRobot ai) {
        if (!restoreReservationAfterLoad) return;
        if (waitingForStation) {
            restoreReservationAfterLoad = false;
            return;
        }
        DockingStation station = getStation();
        if (station == null) return; // Wait for station registry/chunk to load.
        restoreReservationAfterLoad = false;
        if (station == robot.getLinkedStation() || station == robot.getDockingStation()) return;
        if (station.take(robot)) {
            reservedStation = true;
            if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_RESERVATION_RESTORED", "station=%s", station);
        } else {
            // A concurrent claim during world load should be retried, not orphan the job.
            abortDelegateAI();
            waitingForStation = true;
            ticksUntilRetry = RETRY_TICKS;
            if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_RESTORE_WAIT", "station=%s", station);
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        DockingStation station = getStation();
        if (station == null) {
            setSuccess(false);
            terminate();
        } else if (ai instanceof AIRobotGotoBlock) {
            if (ai.success()) {
                if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_APPROACH_DONE", "station=%s", station);
                Direction side = station.side();
                int dx = side == null ? 0 : side.getStepX();
                int dy = side == null ? 0 : side.getStepY();
                int dz = side == null ? 0 : side.getStepZ();
                startDelegateAI(new AIRobotStraightMoveTo(robot, station.x() + 0.5D + dx * 0.5D,
                        station.y() + 0.5D + dy * 0.5D, station.z() + 0.5D + dz * 0.5D));
            } else {
                releaseReservation(station);
                setSuccess(false);
                terminate();
            }
        } else if (ai instanceof AIRobotStraightMoveTo) {
            if (ai.success()) {
                setSuccess(true);
                robot.setDeltaMovement(Vec3.ZERO);
                if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_DOCK_SUCCESS", "station=%s", station);
                if (station.side() != null && station.side().getStepY() == 0) {
                    robot.aimItemAt(station.x() + 2 * station.side().getStepX(), station.y(), station.z() + 2 * station.side().getStepZ());
                }
                robot.dock(station);
            } else {
                releaseReservation(station);
                setSuccess(false);
            }
            terminate();
        }
    }

    private double pathRangeTo(int x, int y, int z) {
        double dx = robot.getX() - (x + 0.5D);
        double dy = robot.getY() - (y + 0.5D);
        double dz = robot.getZ() - (z + 0.5D);
        return Math.max(32.0D, Math.sqrt(dx * dx + dy * dy + dz * dz) + 16.0D);
    }

    @Override
    public void end() {
        DockingStation station = getStation();
        if (!reservedStation && station != null && station != robot.getLinkedStation()
            && station != robot.getDockingStation() && station.robotIdTaking() == robot.getRobotId()) {
            // A persisted station can still hold our robot ID before preempt() restores the runtime reference.
            reservedStation = station.take(robot);
        }
        releaseReservation(station);
    }

    private void releaseReservation(DockingStation station) {
        if (reservedStation && station != null && station != robot.getLinkedStation() && robot.getDockingStation() != station) {
            if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_RELEASE", "station=%s", station);
            station.release(robot);
            reservedStation = false;
        }
    }

    private DockingStation getStation() {
        if (stationIndex == null || robot.getRegistry() == null) return null;
        return robot.getRegistry().getStation(stationIndex.toBlockPos(), stationSide);
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        if (stationIndex != null) {
            CompoundTag idx = new CompoundTag();
            stationIndex.writeTo(idx);
            nbt.put("stationIndex", idx);
            nbt.putByte("stationSide", (byte) (stationSide == null ? -1 : stationSide.ordinal()));
            nbt.putBoolean("waitingForStation", waitingForStation);
            nbt.putInt("ticksUntilRetry", ticksUntilRetry);
            nbt.putInt("unavailableRetries", unavailableRetries);
        }
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        restoreReservationAfterLoad = true;
        waitingForStation = nbt.getBoolean("waitingForStation");
        ticksUntilRetry = Math.max(1, nbt.getInt("ticksUntilRetry"));
        unavailableRetries = Math.max(0, nbt.getInt("unavailableRetries"));
        if (nbt.contains("stationIndex")) {
            stationIndex = new BlockIndex(nbt.getCompound("stationIndex"));
            int side = nbt.getByte("stationSide");
            stationSide = side >= 0 && side < Direction.values().length ? Direction.values()[side] : null;
        }
    }
}
