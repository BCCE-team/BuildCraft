package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-thread-only, per-dimension allocator for the shared BLOCK scanner.
 * Route-search and the specialized Builder/entity dispatchers have independent
 * budgets. The reservations below count block attempts, not chunks or CPU time.
 *
 * Demand is published once per active panel tick, and the first real acquire
 * freezes a weighted allocation for that world tick. Repeated acquire calls by
 * one panel cannot multiply its share. Previously registered demand is used
 * when another panel has not yet ticked, avoiding tick-order priority.
 *
 * Every participant receives a small protected share before any weighted
 * work is distributed. When there are more panels than block credits, a
 * rotating set receives one credit per tick: even this overload is fair over
 * time. No background chunk loads, leases or NBT are maintained here.
 */
public final class RobotAdaptiveScanBudget {
    public static final int LEVEL_CAP = 2048;
    public static final int PANEL_CAP = 384;
    public static final int PROTECTED_SLICE = 32;
    private static final int COST_BASELINE_NANOS = 12_000;

    public record Demand(int requested, int queueDeficit, int waitingRobots,
                         int categories, int dirty, int focused, int nanosPerBlock) {
        public Demand {
            requested = Math.max(0, Math.min(PANEL_CAP, requested));
            queueDeficit = Math.max(0, queueDeficit);
            waitingRobots = Math.max(0, waitingRobots);
            categories = Math.max(0, categories);
            dirty = Math.max(0, dirty);
            focused = Math.max(0, focused);
            nanosPerBlock = Math.max(1, nanosPerBlock);
        }

        int weight() {
            // Even a panel with no missing queue slots is still allowed to
            // refresh its discovery catalog and process changed blocks.
            int pressure = Math.min(8, waitingRobots * 2 + queueDeficit / 2);
            int score = 2 + pressure + (dirty > 0 ? 3 : 0)
                    + (focused > 0 ? 1 : 0) + (categories > 1 ? 1 : 0);
            // Actual measured block cost: favor cheap productive passes, but
            // never starve expensive tags/ores/modded block predicates.
            if (nanosPerBlock > COST_BASELINE_NANOS * 3) score = Math.max(1, score * 2 / 3);
            else if (nanosPerBlock < COST_BASELINE_NANOS / 2) score = Math.min(20, score * 5 / 4);
            return Math.max(1, Math.min(20, score));
        }
    }

    private static final class Entry {
        Demand demand;
        long updated;
        Entry(Demand demand, long updated) { this.demand = demand; this.updated = updated; }
    }

    private final Map<Long, Entry> participants = new HashMap<>();
    private final Map<Long, Integer> quotas = new HashMap<>();
    private final Map<Long, Integer> granted = new HashMap<>();
    private long tick = Long.MIN_VALUE;
    private boolean frozen;
    private int totalGranted;

    public void beginTick(long now) {
        if (tick == now) return;
        if (tick != Long.MIN_VALUE && now < tick) participants.clear();
        tick = now;
        quotas.clear();
        granted.clear();
        totalGranted = 0;
        frozen = false;
        // Unloaded or disabled panels are removed explicitly; this TTL is a
        // second defense for unexpected chunk unloads or GC-cleared tiles.
        participants.entrySet().removeIf(entry -> now - entry.getValue().updated > 1L);
    }

    public void publish(long now, long panel, Demand demand) {
        beginTick(now);
        if (demand == null || demand.requested() <= 0) {
            participants.remove(panel);
            // A panel that has finished may give up unused credits; already
            // dispatched work is never retracted or double counted.
            if (frozen) quotas.put(panel, granted.getOrDefault(panel, 0));
            return;
        }
        participants.put(panel, new Entry(demand, now));
    }

    public void remove(long panel) {
        participants.remove(panel);
        if (frozen) quotas.put(panel, granted.getOrDefault(panel, 0));
    }

    /** A grant reserves credits from both the per-panel and per-level limits. */
    public int acquire(long now, long panel, int requested) {
        beginTick(now);
        if (requested <= 0) return 0;
        if (!frozen) distribute(now);
        int limit = quotas.getOrDefault(panel, 0);
        int used = granted.getOrDefault(panel, 0);
        int remaining = Math.min(LEVEL_CAP - totalGranted, Math.max(0, limit - used));
        int amount = Math.max(0, Math.min(Math.max(0, requested), remaining));
        if (amount > 0) {
            granted.put(panel, used + amount);
            totalGranted += amount;
        }
        return amount;
    }

    /** Return unused scan attempts before another panel can request credit. */
    public void refund(long now, long panel, int unused) {
        if (tick != now || unused <= 0) return;
        int used = granted.getOrDefault(panel, 0);
        int amount = Math.min(used, unused);
        if (amount > 0) {
            granted.put(panel, used - amount);
            totalGranted -= amount;
        }
    }

    public int grantedThisTick(long panel) { return granted.getOrDefault(panel, 0); }
    public int quotaThisTick(long panel) { return quotas.getOrDefault(panel, 0); }
    public int totalThisTick() { return totalGranted; }
    public int activeParticipants() { return participants.size(); }

    private void distribute(long now) {
        frozen = true;
        List<Map.Entry<Long, Entry>> active = new ArrayList<>();
        for (var entry : participants.entrySet()) {
            if (entry.getValue().demand.requested() > 0) active.add(entry);
        }
        active.sort(Map.Entry.comparingByKey());
        int count = active.size();
        if (count == 0) return;

        if (count > LEVEL_CAP) {
            // Not every panel can read one block in a 2048-credit tick.
            // Rotate the served block deterministically on each tick.
            int offset = (int) ((Math.floorMod(now, count) * LEVEL_CAP) % count);
            for (int i = 0; i < LEVEL_CAP; i++) {
                var entry = active.get((offset + i) % count);
                quotas.put(entry.getKey(), 1);
            }
            return;
        }

        int protectedShare = Math.max(1, Math.min(PROTECTED_SLICE, LEVEL_CAP / count));
        int remaining = LEVEL_CAP;
        for (var entry : active) {
            int initial = Math.min(entry.getValue().demand.requested(), protectedShare);
            quotas.put(entry.getKey(), initial);
            remaining -= initial;
        }

        // Weighted water filling. Each iteration either grants positive
        // credits or saturates at least one panel's cap. At most 2048 credits
        // are distributed; zero-need and already capped panels are excluded.
        while (remaining > 0) {
            long weights = 0L;
            List<Map.Entry<Long, Entry>> candidates = new ArrayList<>();
            for (var entry : active) {
                int cap = entry.getValue().demand.requested();
                if (quotas.get(entry.getKey()) < cap) {
                    candidates.add(entry);
                    weights += entry.getValue().demand.weight();
                }
            }
            if (candidates.isEmpty()) break;
            int totalRound = remaining;
            int allocated = 0;
            for (var entry : candidates) {
                int assigned = quotas.get(entry.getKey());
                int space = entry.getValue().demand.requested() - assigned;
                int fair = (int) (totalRound * (long) entry.getValue().demand.weight() / weights);
                int allotment = Math.min(space, fair);
                if (allotment > 0) {
                    quotas.put(entry.getKey(), assigned + allotment);
                    allocated += allotment;
                }
            }
            remaining -= allocated;
            if (allocated == 0) {
                // Fractional credits: largest weight first, but rotate ties
                // so even tiny fractional remainder is not fixed to the first
                // registered panel every tick.
                final long offset = Math.floorMod(now, count);
                candidates.sort(Comparator.<Map.Entry<Long, Entry>>comparingInt(
                        e -> e.getValue().demand.weight()).reversed()
                        .thenComparingLong(e -> Math.floorMod(e.getKey() + offset, count)));
                for (var entry : candidates) {
                    if (remaining <= 0) break;
                    quotas.merge(entry.getKey(), 1, Integer::sum);
                    remaining--;
                }
            }
        }
    }
}
