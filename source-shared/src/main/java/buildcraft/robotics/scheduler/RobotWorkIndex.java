package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.LongPredicate;

/**
 * The panel's long-lived discovery catalog, NOT a per-robot execution queue.
 * A discovered target survives time passing, an idle dock and a full robot
 * queue. Only world changes, policy changes and final validation discard it.
 *
 * A generous safety cap prevents a machine in an artificial solid-ore world
 * from exhausting server memory. Normal 7x7 chunk ore deposits fit easily.
 */
public final class RobotWorkIndex {
    public static final int MAX_CANDIDATES_PER_CATEGORY = 65_536;
    private final Map<String, LinkedHashSet<Long>> candidates = new HashMap<>();

    public void observe(String category, long position, long tick) {
        LinkedHashSet<Long> entries = candidates.computeIfAbsent(category, ignored -> new LinkedHashSet<>());
        if (!entries.add(position)) return; // Keep stable order; no LRU churn on rescans.
        if (entries.size() > MAX_CANDIDATES_PER_CATEGORY) {
            Iterator<Long> oldest = entries.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    public void invalidate(String category, long position) {
        var entries = candidates.get(category);
        if (entries != null) entries.remove(position);
    }

    public void forgetUnused(Iterable<String> required) {
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (String category : required) kept.add(category);
        candidates.keySet().removeIf(category -> !kept.contains(category));
    }

    public int count(String category, long tick) {
        var entries = candidates.get(category);
        return entries == null ? 0 : entries.size();
    }

    /** A cached position is not a claim. World/board/lease validity is checked on dispatch. */
    public int countMatching(String category, long tick, LongPredicate available) {
        var entries = candidates.get(category);
        if (entries == null) return 0;
        int count = 0;
        for (long position : entries) if (available.test(position)) count++;
        return count;
    }

    public void invalidatePosition(long position) {
        for (var entries : candidates.values()) entries.remove(position);
    }

    /**
     * Select the nearest N without sorting the entire accumulated catalog.
     * O(number of known targets * log(N)), O(N) auxiliary memory. The order
     * and tie-breaker are deterministic even with a large persistent catalog.
     */
    public List<Long> nearest(String category, int x, int y, int z, long tick, int limit,
            LongPredicate eligible) {
        var entries = candidates.get(category);
        if (entries == null || limit <= 0) return List.of();
        Comparator<Long> nearestFirst = Comparator.comparingLong((Long pos) -> squaredDistance(pos, x, y, z))
                .thenComparingLong(Long::longValue);
        PriorityQueue<Long> farthestFirst = new PriorityQueue<>(Math.min(limit, 128), nearestFirst.reversed());
        for (long pos : entries) {
            if (!eligible.test(pos)) continue;
            if (farthestFirst.size() < limit) farthestFirst.add(pos);
            else if (nearestFirst.compare(pos, farthestFirst.peek()) < 0) {
                farthestFirst.poll();
                farthestFirst.add(pos);
            }
        }
        List<Long> result = new ArrayList<>(farthestFirst);
        result.sort(nearestFirst);
        return result;
    }

    // Mirrors BlockPos's standard long packing, without a Minecraft dependency.
    private static long squaredDistance(long packed, int x, int y, int z) {
        int px = (int) (packed >> 38);
        int py = (int) (packed << 52 >> 52);
        int pz = (int) (packed << 26 >> 38);
        long dx = (long) px - x, dy = (long) py - y, dz = (long) pz - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
