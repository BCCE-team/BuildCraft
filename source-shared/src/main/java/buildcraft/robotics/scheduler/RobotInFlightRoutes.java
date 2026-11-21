package buildcraft.robotics.scheduler;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks ongoing multi-target A* searches within ONE loaded robot processor.
 *
 * <p>Only an <b>identical</b> starting block AND identical set of goals are
 * coalesced. Two robots standing on opposite sides of a wall may be only one
 * block apart, yet have different reachable components. A positive or negative
 * answer is never fabricated here: the native search always makes the final
 * decision. A follower waits only while a leader is actively exploring.</p>
 *
 * <p>All calls are made on the server thread. The leases are deliberately
 * transient, small and bounded; no paths, NBT or loaded chunks are retained.</p>
 */
public final class RobotInFlightRoutes {
    public static final int MAX_IN_FLIGHT = 128;
    public static final int MAX_GOALS = 128;
    public static final long LEASE_TICKS = 40L;
    /** Slow searches must never hold identical peers back indefinitely. */
    public static final long MAX_COALESCING_TICKS = 100L;
    /** -1 = no coordination (over capacity), 0 = wait, positive = owner token. */
    public static final long UNMANAGED = -1L;
    public static final long WAIT = 0L;

    private static final class SearchKey {
        final long start;
        final long[] sortedGoals;
        final int hash;

        SearchKey(long start, long[] goals) {
            this.start = start;
            this.sortedGoals = goals;
            this.hash = 31 * Long.hashCode(start) + Arrays.hashCode(goals);
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object obj) {
            return obj instanceof SearchKey other && start == other.start
                    && Arrays.equals(sortedGoals, other.sortedGoals);
        }
    }

    private static final class Lease {
        final SearchKey key;
        final long robot;
        final long token;
        final long startedAt;
        long expiresAt;
        Lease(SearchKey key, long robot, long token, long now, long expiresAt) {
            this.key = key;
            this.robot = robot;
            this.token = token;
            this.startedAt = now;
            this.expiresAt = expiresAt;
        }
    }

    /** Unique across panel instances, preventing stale cross-panel callbacks. */
    private static final AtomicLong NEXT_TOKEN = new AtomicLong(1L);
    private final Map<SearchKey, Lease> bySearch = new HashMap<>();
    private final Map<Long, Lease> byRobot = new HashMap<>();
    private long lastExpiryCheck = Long.MIN_VALUE;

    /**
     * An exact goal set is required: hashing alone cannot safely distinguish
     * different types of work. Returning UNMANAGED is preferable to retaining
     * a large set or silently skipping a potentially reachable target.
     */
    public long begin(long now, long robotId, long start, Collection<Long> goals) {
        expire(now);
        if (goals == null || goals.isEmpty() || goals.size() > MAX_GOALS) return UNMANAGED;
        HashSet<Long> unique = new HashSet<>(goals);
        if (unique.isEmpty() || unique.size() > MAX_GOALS || unique.contains(null)) return UNMANAGED;
        long[] values = new long[unique.size()];
        int i = 0;
        for (long packed : unique) values[i++] = packed;
        Arrays.sort(values);
        SearchKey key = new SearchKey(start, values);
        Lease mine = byRobot.get(robotId);
        if (mine != null && mine.key.equals(key)) {
            mine.expiresAt = now + LEASE_TICKS;
            return mine.token;
        }
        // Robot began a DIFFERENT job; its old lease must not hide work.
        if (mine != null) unlink(mine);
        Lease incumbent = bySearch.get(key);
        if (incumbent != null) {
            // A multi-tick A* may be throttled to only a few nodes/tick.
            // After a bounded delay followers may search independently;
            // coalescing must not degrade their eventual progress.
            return now - incumbent.startedAt >= MAX_COALESCING_TICKS ? UNMANAGED : WAIT;
        }
        if (bySearch.size() >= MAX_IN_FLIGHT) return UNMANAGED;
        long token = NEXT_TOKEN.getAndUpdate(value -> value == Long.MAX_VALUE ? 1L : value + 1L);
        Lease created = new Lease(key, robotId, token, now, now + LEASE_TICKS);
        byRobot.put(robotId, created);
        bySearch.put(key, created);
        return token;
    }

    /** Fencing prevents a late callback from a cancelled AI removing a new job. */
    public boolean renew(long now, long robotId, long token) {
        expire(now);
        Lease mine = byRobot.get(robotId);
        if (token <= 0 || mine == null || mine.token != token) return false;
        mine.expiresAt = now + LEASE_TICKS;
        return true;
    }

    public boolean finished(long robotId, long token) {
        Lease mine = byRobot.get(robotId);
        if (mine == null || mine.token != token || token <= 0) return false;
        unlink(mine);
        return true;
    }

    public void expire(long now) {
        if (now < lastExpiryCheck) clear();
        if (now == lastExpiryCheck) return;
        lastExpiryCheck = now;
        // Never trust an expired lease after a game pause, player teleport,
        // robot unload, or exception in a native AI.
        byRobot.values().removeIf(lease -> {
            if (lease.expiresAt > now) return false;
            bySearch.remove(lease.key, lease);
            return true;
        });
    }

    public void clear() {
        byRobot.clear();
        bySearch.clear();
        lastExpiryCheck = Long.MIN_VALUE;
    }

    public int activeSearches() { return bySearch.size(); }

    private void unlink(Lease lease) {
        bySearch.remove(lease.key, lease);
        byRobot.remove(lease.robot, lease);
    }
}
