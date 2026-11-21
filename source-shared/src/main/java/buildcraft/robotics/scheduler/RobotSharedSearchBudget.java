package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-world, per-server-tick CPU envelope for panel-managed route searches.
 * This class has no Minecraft dependencies so fairness can be JVM-tested.
 * It is only accessed from the server thread. Autonomous robots are not gated.
 *
 * When an exceptionally large fleet competes for the same server budget,
 * rotating groups get usable 32-node slices instead of permanently starving
 * later robot IDs with thousands of one-node searches per tick.
 */
public final class RobotSharedSearchBudget {
    public static final int MAX_NODES_PER_TICK = 3_072;
    private static final int MAX_NODES_PER_ROBOT_TICK = 1_024;
    private static final int MIN_BURST = 32;
    private static final int ROTATING_SLOTS = MAX_NODES_PER_TICK / MIN_BURST;

    private long tick = Long.MIN_VALUE;
    private int remaining = MAX_NODES_PER_TICK;
    private final Map<Long, Integer> grants = new HashMap<>();
    private final Set<Long> refunded = new HashSet<>();
    private Set<Long> currentRotation = null;

    private void begin(long gameTick) {
        if (gameTick != tick) {
            tick = gameTick;
            remaining = MAX_NODES_PER_TICK;
            grants.clear();
            refunded.clear();
            currentRotation = null;
        }
    }

    /**
     * A stable sorted snapshot of loaded SEARCHING workers is prepared once
     * when the panel examines its live registry. Only when >96 jobs compete
     * do we rotate which workers are eligible this tick. A worker excluded
     * now is guaranteed an opportunity on a later tick while it stays active.
     */
    public void setActiveWorkers(long gameTick, Collection<Long> robotIds) {
        begin(gameTick);
        if (robotIds == null || robotIds.size() <= ROTATING_SLOTS) {
            currentRotation = null;
            return;
        }
        ArrayList<Long> sorted = new ArrayList<>(new HashSet<>(robotIds));
        if (sorted.size() <= ROTATING_SLOTS) {
            currentRotation = null;
            return;
        }
        Collections.sort(sorted);
        int size = sorted.size();
        // floorMod avoids overflow even on very old worlds with large ticks.
        int offset = (int) ((Math.floorMod(gameTick, (long) size) * ROTATING_SLOTS) % size);
        HashSet<Long> selected = new HashSet<>(ROTATING_SLOTS * 2);
        for (int i = 0; i < ROTATING_SLOTS; i++) {
            selected.add(sorted.get((offset + i) % size));
        }
        currentRotation = selected;
    }

    /**
     * Prevent one miner from repeatedly taking the entire tick budget.
     * activeMiners includes only loaded, working miners under loaded panels.
     */
    public int acquire(long gameTick, long robotId, int activeMiners, int requested) {
        begin(gameTick);
        if (requested <= 0 || remaining <= 0) return 0;
        if (currentRotation != null && !currentRotation.contains(robotId)) return 0;
        int fairShare = currentRotation == null
                ? Math.min(MAX_NODES_PER_ROBOT_TICK,
                        Math.max(1, MAX_NODES_PER_TICK / Math.max(1, activeMiners)))
                : MIN_BURST;
        int already = grants.getOrDefault(robotId, 0);
        int allowance = Math.max(0, fairShare - already);
        int grant = Math.min(Math.min(requested, allowance), remaining);
        if (grant != 0) {
            grants.put(robotId, already + grant);
            remaining -= grant;
        }
        return grant;
    }

    /**
     * A search that finishes after examining only a few frontier entries
     * returns the rest of its reservation. The original per-robot grant is
     * NOT reduced: the same robot cannot immediately take another grant, and
     * duplicate completion callbacks cannot mint artificial CPU credits.
     */
    public int returnUnused(long gameTick, long robotId, int allocated, int attempted) {
        if (gameTick != tick || allocated <= 0 || attempted >= allocated || !refunded.add(robotId)) {
            return 0;
        }
        int unused = Math.min(Math.max(0, allocated - Math.max(0, attempted)),
                grants.getOrDefault(robotId, 0));
        remaining = Math.min(MAX_NODES_PER_TICK, remaining + unused);
        return unused;
    }

    public int remaining() { return remaining; }
}
