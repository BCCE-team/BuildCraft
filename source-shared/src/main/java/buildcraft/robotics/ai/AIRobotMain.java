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
        // Returning to a lost home station is a safety operation. It must not be replaced by recharge/shutdown,
        // otherwise a discharged robot can remain stranded forever at its last work target.
        if (ai instanceof AIRobotReturnToLostStation) {
            return;
        }

        // Once a low-power return has started, never replace it with shutdown/recharge. AIRobotGotoSleep is allowed
        // to finish even if the battery reaches zero, and AIRobotSleep then remains stable until the dock recharges it.
        if (ai instanceof AIRobotGotoSleep || ai instanceof AIRobotSleep) {
            return;
        }

        DockingStation home = robot.getLinkedStation();
        if (home != null && robot.getEnergy() <= getReturnEnergyThreshold(home)) {
            // Low-power return is an abort, not a pause: do not resume a stale player/board override after charging.
            overridingAI = null;
            startDelegateAI(new AIRobotGotoSleep(robot));
            return;
        }

        if (robot.getEnergy() <= EntityRobotBase.SHUTDOWN_ENERGY
                && (robot.getDockingStation() == null || !robot.getDockingStation().providesPower())) {
            if (!(ai instanceof AIRobotShutdown)) {
                startDelegateAI(new AIRobotShutdown(robot));
            }
        } else if (robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY) {
            if (!(ai instanceof AIRobotRecharge) && !(ai instanceof AIRobotShutdown)) {
                if (rechargeCooldown-- <= 0) {
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
