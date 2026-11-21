package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
    /** Versioned persistent catalog is deliberately smaller than an unbounded NBT blob. */
    public static final int CATALOG_FORMAT = 1;
    public static final int MAX_SAVED_CATEGORIES = 32;
    public static final int MAX_SAVED_POSITIONS = 131_072;
    private static final int MAX_CATEGORY_NAME = 96;
    private final Map<String, LinkedHashSet<Long>> candidates = new HashMap<>();
    private long generation;

    /** Data only: no robot IDs, leases, world state or live chunk references. */
    public record CatalogSnapshot(String categories, int[] counts, long[] positions) {
        public CatalogSnapshot {
            categories = categories == null ? "" : categories;
            counts = counts == null ? new int[0] : counts.clone();
            positions = positions == null ? new long[0] : positions.clone();
            if (categories.length() > MAX_SAVED_CATEGORIES * (MAX_CATEGORY_NAME + 1)
                    || counts.length > MAX_SAVED_CATEGORIES || positions.length > MAX_SAVED_POSITIONS) {
                categories = "";
                counts = new int[0];
                positions = new long[0];
            }
        }
        @Override public int[] counts() { return counts.clone(); }
        @Override public long[] positions() { return positions.clone(); }
        public static CatalogSnapshot empty() { return new CatalogSnapshot("", new int[0], new long[0]); }
    }

    /** Mutations are tracked without a chunk save or snapshot on every scan tick. */
    public long generation() { return generation; }

    /** A compact stable snapshot; during normal play this is only built on chunk saves. */
    public CatalogSnapshot snapshot() {
        List<String> categories = new ArrayList<>();
        for (var entry : candidates.entrySet()) {
            if (!entry.getValue().isEmpty() && validCategory(entry.getKey())) categories.add(entry.getKey());
        }
        Collections.sort(categories);
        if (categories.size() > MAX_SAVED_CATEGORIES) {
            categories = categories.subList(0, MAX_SAVED_CATEGORIES);
        }
        int[] counts = new int[categories.size()];
        int total = 0;
        for (int i = 0; i < categories.size(); i++) {
            counts[i] = Math.min(candidates.get(categories.get(i)).size(), MAX_SAVED_POSITIONS - total);
            total += counts[i];
        }
        long[] positions = new long[total];
        int cursor = 0;
        for (int i = 0; i < categories.size(); i++) {
            int left = counts[i];
            for (long packed : candidates.get(categories.get(i))) {
                if (left-- <= 0) break;
                positions[cursor++] = packed;
            }
        }
        return new CatalogSnapshot(String.join("\n", categories), counts, positions);
    }

    /**
     * Decode untrusted NBT defensively. Coordinates are only potential work;
     * `allowed` checks the current panel's area and build height WITHOUT
     * fetching or force-loading chunks. Actual block/board/lease checks run
     * later, immediately before scheduling or execution.
     */
    public int restore(CatalogSnapshot snapshot, LongPredicate allowed) {
        if (snapshot == null || allowed == null) return 0;
        String names = snapshot.categories();
        if (names.isEmpty()) return 0;
        String[] categories = names.split("\n", -1);
        int[] counts = snapshot.counts();
        long[] positions = snapshot.positions();
        if (categories.length == 0 || categories.length != counts.length
                || categories.length > MAX_SAVED_CATEGORIES || positions.length > MAX_SAVED_POSITIONS) return 0;
        Set<String> unique = new HashSet<>();
        long total = 0;
        for (int i = 0; i < counts.length; i++) {
            if (!validCategory(categories[i]) || !unique.add(categories[i])
                    || counts[i] < 0 || counts[i] > MAX_CANDIDATES_PER_CATEGORY) return 0;
            total += counts[i];
            if (total > MAX_SAVED_POSITIONS) return 0;
        }
        if (total != positions.length) return 0;
        int restored = 0;
        int offset = 0;
        for (int i = 0; i < categories.length; i++) {
            String category = categories[i];
            for (int n = 0; n < counts[i]; n++) {
                long packed = positions[offset++];
                if (!allowed.test(packed)) continue;
                LinkedHashSet<Long> existing = candidates.computeIfAbsent(category, ignored -> new LinkedHashSet<>());
                if (existing.size() < MAX_CANDIDATES_PER_CATEGORY && existing.add(packed)) {
                    generation++;
                    restored++;
                }
            }
        }
        return restored;
    }

    private static boolean validCategory(String category) {
        return category != null && category.length() > 0 && category.length() <= MAX_CATEGORY_NAME
                && category.matches("[A-Za-z0-9_.:/-]+");
    }


    public void observe(String category, long position, long tick) {
        LinkedHashSet<Long> entries = candidates.computeIfAbsent(category, ignored -> new LinkedHashSet<>());
        if (!entries.add(position)) return; // Keep stable order; no LRU churn on rescans.
        generation++;
        if (entries.size() > MAX_CANDIDATES_PER_CATEGORY) {
            Iterator<Long> oldest = entries.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    public void invalidate(String category, long position) {
        var entries = candidates.get(category);
        if (entries != null && entries.remove(position)) generation++;
    }

    public void forgetUnused(Iterable<String> required) {
        java.util.Set<String> kept = new java.util.HashSet<>();
        for (String category : required) kept.add(category);
        if (candidates.keySet().removeIf(category -> !kept.contains(category))) generation++;
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

    /** Debug-only height distribution. No Minecraft classes or world reads. */
    public record HeightExtent(int total, int below, int minY, int maxY) { }

    public HeightExtent heightExtent(String category, int referenceY) {
        var entries = candidates.get(category);
        if (entries == null || entries.isEmpty()) return new HeightExtent(0, 0, 0, 0);
        int below = 0, minimum = Integer.MAX_VALUE, maximum = Integer.MIN_VALUE;
        for (long packed : entries) {
            int y = (int) (packed << 52 >> 52);
            if (y < referenceY) below++;
            minimum = Math.min(minimum, y);
            maximum = Math.max(maximum, y);
        }
        return new HeightExtent(entries.size(), below, minimum, maximum);
    }

    public void invalidatePosition(long position) {
        boolean removed = false;
        for (var entries : candidates.values()) removed |= entries.remove(position);
        if (removed) generation++;
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
