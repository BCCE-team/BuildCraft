package buildcraft.robotics.scheduler;

import buildcraft.api.v2.energy.MjAmount;

/** Server-side power budget for a robot control panel, in micro-MJ per tick. */
public final class RobotProcessorPower {
    public static final int MAX_ROBOTS = 16;
    public static final long CAPACITY = 5_000L * MjAmount.MICRO_MJ_PER_MJ;
    public static final long BASE_PER_TICK = 8L * MjAmount.MICRO_MJ_PER_MJ;
    public static final long ROBOT_PER_TICK = 2L * MjAmount.MICRO_MJ_PER_MJ;
    public static final long MAX_PER_TICK = BASE_PER_TICK + MAX_ROBOTS * ROBOT_PER_TICK;

    private RobotProcessorPower() { }

    public static long requiredPerTick(int loadedRobots) {
        if (loadedRobots <= 0) return 0;
        return BASE_PER_TICK + Math.min(MAX_ROBOTS, loadedRobots) * ROBOT_PER_TICK;
    }
}
