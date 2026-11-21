package buildcraft.robotics.scheduler;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-world, per-server-tick CPU envelope for panel-managed route searches.
 * This class has no Minecraft dependencies so fairness can be JVM-tested.
 * It is only accessed from the server thread. Autonomous robots are not gated.
 */
public final class RobotSharedSearchBudget {
    public static final int MAX_NODES_PER_TICK = 3_072;
    private static final int MAX_NODES_PER_ROBOT_TICK = 1_024;
    private long tick = Long.MIN_VALUE;
    private int remaining = MAX_NODES_PER_TICK;
    private final Map<Long, Integer> grants = new HashMap<>();

    /**
     * Prevent one miner from repeatedly taking the entire tick budget.
     * activeMiners includes only loaded, working miners under loaded panels.
     */
    public int acquire(long gameTick, long robotId, int activeMiners, int requested) {
        if (gameTick != tick) {
            tick = gameTick;
            remaining = MAX_NODES_PER_TICK;
            grants.clear();
        }
        if (requested <= 0 || remaining <= 0) return 0;
        int fairShare = Math.min(MAX_NODES_PER_ROBOT_TICK,
                Math.max(1, MAX_NODES_PER_TICK / Math.max(1, activeMiners)));
        int already = grants.getOrDefault(robotId, 0);
        int allowance = Math.max(0, fairShare - already);
        int grant = Math.min(Math.min(requested, allowance), remaining);
        if (grant != 0) {
            grants.put(robotId, already + grant);
            remaining -= grant;
        }
        return grant;
    }

    public int remaining() { return remaining; }
}
