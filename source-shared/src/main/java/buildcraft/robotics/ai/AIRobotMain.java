package buildcraft.robotics.ai;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.core.Direction;

/** Main robotics AI loop matching BuildCraft 7.1.x behavior. */
public class AIRobotMain extends AIRobot {
    private static final double MOVE_SPEED_PER_TICK = 0.15D;
    private static final int MOVE_ENERGY_PER_TICK = 3;
    private static final int RETURN_FIXED_RESERVE = 2_000;

    private AIRobot overridingAI;
    private int rechargeCooldown;

    public AIRobotMain(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public int getEnergyCost() {
        return 0;
    }

    @Override
    public void preempt(AIRobot ai) {
        // Lost-home recovery is a separate emergency: a missing station must be resolved before work/recharge.
        if (ai instanceof AIRobotReturnToLostStation) {
            return;
        }

        int energy = robot.getEnergy();
        DockingStation docked = robot.getDockingStation();
        boolean dockedAtCharger = docked != null && docked.providesPower();
        DockingStation home = robot.getLinkedStation();

        // A home station is not necessarily a charging station. The old low-energy return sent robots home
        // unconditionally, even when the nearby powered station was somewhere else. Such robots reached AIRobotSleep
        // and could never reach SAFETY_ENERGY or respond to Wake Up. Retain the early-return energy reserve, but let
        // AIRobotRecharge locate the nearest *powered* station, just as original BuildCraft did.
        boolean needsRecharge = energy < EntityRobotBase.SAFETY_ENERGY
                || (home != null && home.providesPower() && energy <= getReturnEnergyThreshold(home));

        if (ai instanceof AIRobotGotoSleep || ai instanceof AIRobotSleep) {
            // Older saves may already contain the non-charging sleep/return path. A robot sleeping at a powered dock
            // should stay there until charged; a robot stranded at an unpowered dock must search for a charger.
            // Keep a zero-energy emergency return to a powered home in progress, as in the previous safety fix.
            if (!dockedAtCharger && needsRecharge
                    && !(ai instanceof AIRobotGotoSleep && energy <= EntityRobotBase.SHUTDOWN_ENERGY
                         && home != null && home.providesPower())) {
                overridingAI = null;
                startDelegateAI(energy <= EntityRobotBase.SHUTDOWN_ENERGY
                        ? new AIRobotShutdown(robot) : new AIRobotRecharge(robot));
            }
            return;
        }

        if (energy <= EntityRobotBase.SHUTDOWN_ENERGY && !dockedAtCharger) {
            if (!(ai instanceof AIRobotShutdown)) {
                startDelegateAI(new AIRobotShutdown(robot));
            }
        } else if (needsRecharge) {
            if (!(ai instanceof AIRobotRecharge) && !(ai instanceof AIRobotShutdown)) {
                if (rechargeCooldown-- <= 0) {
                    // Recharge is an abort, not a pause: do not resume stale gate/board work before charging.
                    overridingAI = null;
                    startDelegateAI(new AIRobotRecharge(robot));
                }
            }
        } else if (!(ai instanceof AIRobotRecharge)) {
            if (overridingAI != null && ai != overridingAI) {
                startDelegateAI(overridingAI);
            }
        }
    }

    private int getReturnEnergyThreshold(DockingStation station) {
        Direction side = station.side();
        int dx = side == null ? 0 : side.getStepX();
        int dy = side == null ? 0 : side.getStepY();
        int dz = side == null ? 0 : side.getStepZ();

        double targetX = station.x() + 0.5D + dx * 0.5D;
        double targetY = station.y() + 0.5D + dy * 0.5D;
        double targetZ = station.z() + 0.5D + dz * 0.5D;
        double pathBlocks = Math.abs(robot.getX() - targetX)
                + Math.abs(robot.getY() - targetY)
                + Math.abs(robot.getZ() - targetZ);

        long movementTicks = (long) Math.ceil(pathBlocks / MOVE_SPEED_PER_TICK);
        long estimatedMovement = movementTicks * MOVE_ENERGY_PER_TICK;
        long withMargin = estimatedMovement + estimatedMovement / 2L + RETURN_FIXED_RESERVE;
        long threshold = Math.max(EntityRobotBase.SAFETY_ENERGY, withMargin);
        return (int) Math.min(EntityRobotBase.MAX_ENERGY - 1L, threshold);
    }

    @Override
    public void update() {
        RedstoneBoardRobot board = robot.getBoard();
        if (board != null) {
            startDelegateAI(board);
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotRecharge && !ai.success()) {
            rechargeCooldown = 120;
        }
        if (ai == overridingAI) {
            overridingAI = null;
        }
    }

    public void setOverridingAI(AIRobot ai) {
        if (ai == null) {
            overridingAI = null;
        } else if (overridingAI == null) {
            overridingAI = ai;
        }
    }

    public AIRobot getOverridingAI() {
        return overridingAI;
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }
}
