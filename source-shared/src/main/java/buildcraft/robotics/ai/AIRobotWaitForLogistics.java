package buildcraft.robotics.ai;

import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.scheduler.RobotLogisticsScheduler;

/**
 * Wait for an asynchronously discovered logistics assignment without consuming
 * the emergency energy reserve. Exiting the controller immediately restores
 * the ordinary autonomous behavior of the installed carrier/delivery board.
 */
public class AIRobotWaitForLogistics extends AIRobot {
    private int ticks;
    private int elapsed;
    private static final int NATIVE_FALLBACK_TICKS = 200;

    public AIRobotWaitForLogistics(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public void update() {
        elapsed++;
        if (++ticks >= 20) {
            ticks = 0;
            if (!RobotLogisticsScheduler.hasController(robot)
                    || RobotLogisticsScheduler.hasQueuedJob(robot)) {
                setSuccess(true);
                terminate();
            } else if (elapsed >= NATIVE_FALLBACK_TICKS) {
                // Predicate-only API requests and third-party capabilities may
                // not expose an example to the bounded shared scanner. Preserve
                // their native search as an occasional compatibility fallback.
                setSuccess(false);
                terminate();
            }
        }
    }

    @Override
    public int getEnergyCost() {
        return 0;
    }
}
