package buildcraft.robotics.ai;

import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Gate-command AI from BuildCraft 7.1.x: move the robot to the requested station and
 * make that station its main dock. Unlike a temporary AIRobotGotoStation, this relinks home.
 */
public class AIRobotGoAndLinkToDock extends AIRobot {
    private static final int RETURN_RETRY_TICKS = 40;

    private BlockIndex stationIndex;
    private Direction stationSide;
    private boolean waitingToRetry;
    private int retryTicks;
    /** Keep a continuously commanded dock request satisfied until the Gate stops demanding it. */
    private boolean holdingAtStation;
    private long lastGateSignalTick = Long.MIN_VALUE;

    /** Minecraft 1.19.2 exposes Entity.level as a field; later versions use level(). */
    private Level robotLevel() {
        //? if <1.20.1 {
        return robot.level;
        //?} else {
        return robot.level();
        //?}
    }

    /** Called by AIRobotMain for every matching activation of the same Gate command. */
    public void refreshGateSignal() {
        lastGateSignalTick = robotLevel().getGameTime();
    }

    public boolean isHoldingAtStation() {
        return holdingAtStation;
    }

    private void holdAtStation() {
        waitingToRetry = false;
        holdingAtStation = true;
        retryTicks = 0;
        robot.setDeltaMovement(Vec3.ZERO);
        if (RobotTrace.enabled()) RobotTrace.event(robot, "DOCK_RETURN_HOLD", "station=%s", getStation());
    }

    public AIRobotGoAndLinkToDock(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotGoAndLinkToDock(EntityRobotBase robot, DockingStation station) {
        this(robot);
        if (station != null) {
            stationIndex = station.index();
            stationSide = station.side();
        }
    }

    /** Identical persistent gate commands must not restart an active docking route every tick. */
    public boolean sameDestination(AIRobotGoAndLinkToDock other) {
        if (other == null || stationIndex == null || other.stationIndex == null) return false;
        return stationIndex.x == other.stationIndex.x
                && stationIndex.y == other.stationIndex.y
                && stationIndex.z == other.stationIndex.z
                && stationSide == other.stationSide;
    }

    @Override
    public void start() {
        refreshGateSignal();
        attemptDocking();
    }

    private void attemptDocking() {
        DockingStation station = getStation();
        if (station == null || !station.isInitialized()) {
            retryLater("station unavailable");
            return;
        }
        if (station == robot.getLinkedStation() && station == robot.getDockingStation()) {
            holdAtStation();
            return;
        }
        if (!station.takeAsMain(robot)) {
            retryLater("station occupied or not ready");
            return;
        }
        waitingToRetry = false;
        retryTicks = 0;
        // Relinking a robot already physically docked at the requested station
        // must not undock it just to fly back to the same socket.
        if (robot.getDockingStation() == station) {
            holdAtStation();
            return;
        }

        // Match the temporary station AI: the approach node is one block outside the
        // station face, not two. A distant point can itself be inside the ground.
        Direction side = station.side();
        int x = station.x() + (side == null ? 0 : side.getStepX());
        int y = station.y() + (side == null ? 0 : side.getStepY());
        int z = station.z() + (side == null ? 0 : side.getStepZ());
        startDelegateAI(new AIRobotGotoBlock(robot, x, y, z, pathRangeTo(x, y, z))
                .forStationReturn(station.x(), station.y(), station.z()));
    }

    private void retryLater(String reason) {
        waitingToRetry = true;
        retryTicks = RETURN_RETRY_TICKS;
        robot.setDeltaMovement(Vec3.ZERO);
        if (RobotTrace.enabled()) RobotTrace.event(robot, "DOCK_RETURN_RETRY",
                "station=%s reason=%s retryIn=%d", getStation(), reason, retryTicks);
    }

    @Override
    public void update() {
        if (holdingAtStation) {
            long now = robotLevel().getGameTime();
            // A Gate action is level-triggered. Keep the robot docked while it is
            // active; otherwise the board undocks it one tick later and the Gate
            // immediately sends it back again. A missed full tick releases the hold.
            if (lastGateSignalTick == Long.MIN_VALUE || now - lastGateSignalTick > 1L) {
                holdingAtStation = false;
                if (RobotTrace.enabled()) RobotTrace.event(robot, "DOCK_RETURN_RELEASE", "gate signal ended");
                terminate();
                return;
            }
            DockingStation station = getStation();
            if (station == null || station != robot.getDockingStation() || station != robot.getLinkedStation()) {
                holdingAtStation = false;
                attemptDocking();
            }
            return;
        }
        if (!waitingToRetry || --retryTicks <= 0) {
            waitingToRetry = false;
            attemptDocking();
        }
    }

    @Override
    public int getEnergyCost() {
        return waitingToRetry || holdingAtStation ? 0 : 1;
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        DockingStation station = getStation();
        if (station == null || !station.isInitialized()) {
            retryLater("station lost while moving");
            return;
        }
        if (ai instanceof AIRobotGotoBlock) {
            if (ai.success()) {
                startFinalDockMove(station);
            } else {
                // If the world genuinely changed or no traversable route exists,
                // wait and recompute. Never fly through solid terrain.
                retryLater("no passable route to station");
            }
        } else if (ai instanceof AIRobotStraightMoveTo) {
            if (ai.success()) {
                Direction side = station.side();
                if (side != null && side.getStepY() == 0) {
                    robot.aimItemAt(station.x() + 2 * side.getStepX(), station.y(), station.z() + 2 * side.getStepZ());
                } else {
                    robot.aimItemAt(Mth.floor(robot.getAimYaw() / 90.0F) * 90.0F + 180.0F, robot.getAimPitch());
                }
                robot.dock(station);
                if (robot.getDockingStation() == station) {
                    if (RobotTrace.enabled()) RobotTrace.event(robot, "DOCK_RETURN_SUCCESS", "station=%s", station);
                    holdAtStation();
                } else {
                    retryLater("dock not acknowledged");
                }
            } else {
                retryLater("final docking movement stalled");
            }
        }
    }

    private void startFinalDockMove(DockingStation station) {
        Direction side = station.side();
        int dx = side == null ? 0 : side.getStepX();
        int dy = side == null ? 0 : side.getStepY();
        int dz = side == null ? 0 : side.getStepZ();
        startDelegateAI(new AIRobotStraightMoveTo(robot,
                station.x() + 0.5D + dx * 0.5D,
                station.y() + 0.5D + dy * 0.5D,
                station.z() + 0.5D + dz * 0.5D));
    }

    private double pathRangeTo(int x, int y, int z) {
        double dx = robot.getX() - (x + 0.5D);
        double dy = robot.getY() - (y + 0.5D);
        double dz = robot.getZ() - (z + 0.5D);
        return Math.max(32.0D, Math.sqrt(dx * dx + dy * dy + dz * dz) + 16.0D);
    }

    private DockingStation getStation() {
        if (stationIndex == null) return robot.getLinkedStation();
        // Never redirect an explicitly requested station to some other dock
        // just because the registry is temporarily unavailable.
        if (robot.getRegistry() == null) return null;
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
        }
        nbt.putBoolean("waitingToRetry", waitingToRetry);
        nbt.putInt("retryTicks", retryTicks);
        nbt.putBoolean("holdingAtStation", holdingAtStation);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("stationIndex")) {
            stationIndex = new BlockIndex(nbt.getCompound("stationIndex"));
            int side = nbt.getByte("stationSide");
            stationSide = side >= 0 && side < Direction.values().length ? Direction.values()[side] : null;
        } else if (robot.getLinkedStation() != null) {
            stationIndex = robot.getLinkedStation().index();
            stationSide = robot.getLinkedStation().side();
        }
        waitingToRetry = nbt.getBoolean("waitingToRetry");
        retryTicks = waitingToRetry ? Math.max(1, nbt.getInt("retryTicks")) : 0;
        holdingAtStation = nbt.getBoolean("holdingAtStation");
        // A saved Gate must not hold a robot forever when the signal is gone.
        lastGateSignalTick = robotLevel().getGameTime();
    }
}
