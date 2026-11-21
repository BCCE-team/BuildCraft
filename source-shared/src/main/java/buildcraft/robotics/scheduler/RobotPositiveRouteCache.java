package buildcraft.robotics.scheduler;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Bounded positive A* route witnesses for one loaded robot processor.
 *
 * <p>This is a hint, not authority to mine a block or move through a wall.
 * A reader MUST recheck every waypoint against loaded world state and then
 * recheck the goal with its own tool, zone, Gate and registry predicates.
 * After any world mutation a witness may still seed A* through its validated
 * corridor, but can no longer short-circuit the shortest-path search: a newly
 * opened passage might have made another route cheaper.</p>
 *
 * <p>Only primitive coordinates are retained. No world/robot/AI references,
 * persistent NBT data, or chunk tickets are created.</p>
 */
public final class RobotPositiveRouteCache {
    public static final int MAX_ROUTES = 64;
    public static final int MAX_GOALS = 128;
    public static final int MAX_WAYPOINTS = 256;
    public static final long TTL_TICKS = 200L;

    /** Immutable entry; arrays are owned by this cache and never exposed. */
    public static final class Route {
        private final long start;
        private final long target;
        private final long[] sortedGoals;
        private final long[] waypoints;
        private final boolean complete;
        private final long revision;
        private final long expiresAt;
        private final int referenceExpandedNodes;
        private final int minX, maxX, minY, maxY, minZ, maxZ;

        private Route(long start, long target, long[] sortedGoals, long[] waypoints,
                boolean complete, long revision, long expiresAt, int originalExpanded) {
            this.start = start;
            this.target = target;
            this.sortedGoals = sortedGoals;
            this.waypoints = waypoints;
            this.complete = complete;
            this.revision = revision;
            this.expiresAt = expiresAt;
            this.referenceExpandedNodes = Math.max(0, originalExpanded);
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE;
            int y0 = Integer.MAX_VALUE, y1 = Integer.MIN_VALUE;
            int z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (long waypoint : waypoints) {
                int x = x(waypoint), y = y(waypoint), z = z(waypoint);
                x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                z0 = Math.min(z0, z); z1 = Math.max(z1, z);
            }
            minX = x0; maxX = x1;
            minY = y0; maxY = y1;
            minZ = z0; maxZ = z1;
        }

        private boolean mayConnect(long packed) {
            int px = x(packed), py = y(packed), pz = z(packed);
            return (long) px >= (long) minX - 1 && (long) px <= (long) maxX + 1
                    && (long) py >= (long) minY - 1 && (long) py <= (long) maxY + 1
                    && (long) pz >= (long) minZ - 1 && (long) pz <= (long) maxZ + 1;
        }

        private boolean covers(long packed) {
            int px = x(packed), py = y(packed), pz = z(packed);
            return px >= minX && px <= maxX && py >= minY && py <= maxY
                    && pz >= minZ && pz <= maxZ;
        }

        public long start() { return start; }
        public long target() { return target; }
        public long[] waypoints() { return waypoints.clone(); }
        public int length() { return waypoints.length; }
        public boolean complete() { return complete; }
        public long expiresAt() { return expiresAt; }
        /** Original A* effort: a reference estimate, NOT guaranteed savings. */
        public int referenceExpandedNodes() { return referenceExpandedNodes; }
    }

    /** startOffset = number of stored waypoints preceding the new robot start.
     * For an interior waypoint start, skip that waypoint; for a neighbouring
     * cell, keep the first adjacent waypoint. A neighbour is warm-only because
     * its shortest-path cost has NOT been proven by the original A* search. */
    public record Candidate(Route route, boolean exact, int startOffset) {
        public Candidate(Route route, boolean exact) { this(route, exact, 0); }
    }

    private final ArrayDeque<Route> routes = new ArrayDeque<>();
    private long revision;
    private long lastTick = Long.MIN_VALUE;

    private static long[] goals(Collection<Long> input) {
        if (input == null || input.isEmpty() || input.size() > MAX_GOALS) return null;
        HashSet<Long> distinct = new HashSet<>();
        for (Long goal : input) {
            if (goal == null) return null;
            distinct.add(goal);
        }
        if (distinct.isEmpty() || distinct.size() > MAX_GOALS) return null;
        long[] sorted = new long[distinct.size()];
        int i = 0;
        for (long packed : distinct) sorted[i++] = packed;
        Arrays.sort(sorted);
        return sorted;
    }

    /**
     * Store the route's proven passable waypoints (excluding its initial cell
     * and solid target). Routes over the memory cap retain a prefix only; such
     * entries may seed A* but never skip it.
     */
    public boolean remember(long now, long start, Collection<Long> goalPositions,
            long target, Collection<Long> routeWaypoints) {
        return remember(now, start, goalPositions, target, routeWaypoints, 0);
    }

    public boolean remember(long now, long start, Collection<Long> goalPositions,
            long target, Collection<Long> routeWaypoints, int originalExpanded) {
        long[] targetSet = goals(goalPositions);
        if (targetSet == null || Arrays.binarySearch(targetSet, target) < 0
                || routeWaypoints == null) return false;
        expire(now);
        long[] corridor = new long[Math.min(MAX_WAYPOINTS, routeWaypoints.size())];
        int n = 0;
        for (Long pos : routeWaypoints) {
            if (pos == null) return false;
            if (n >= corridor.length) break;
            corridor[n++] = pos;
        }
        // A direct-neighbor target requires no expensive A* to reuse.
        if (corridor.length == 0) return false;
        boolean complete = routeWaypoints.size() <= MAX_WAYPOINTS;
        Route route = new Route(start, target, targetSet, corridor, complete,
                revision, now + TTL_TICKS, originalExpanded);
        routes.removeIf(old -> old.start == start && Arrays.equals(old.sortedGoals, targetSet));
        if (routes.size() == MAX_ROUTES) routes.removeFirst();
        routes.addLast(route);
        return true;
    }

    /**
     * Prefer an exact, unchanged route; otherwise supply a corridor whose
     * endpoint is closest to the new goal set. A corridor hint only warms A*.
     */
    public Candidate find(long now, long start, Collection<Long> goalPositions) {
        long[] currentGoals = goals(goalPositions);
        if (currentGoals == null) return null;
        expire(now);
        Candidate best = null;
        long bestScore = Long.MAX_VALUE;
        long startDistance = nearestManhattan(start, currentGoals);
        for (Iterator<Route> iterator = routes.descendingIterator(); iterator.hasNext();) {
            Route route = iterator.next();
            int offset = route.start == start ? 0 : -1;
            boolean onRoute = route.start == start;
            if (offset < 0 && route.mayConnect(start)) {
                // Prefer the latest continuous suffix. An interior path node
                // inherits optimal-subpath proof; a 1-block connector does NOT.
                for (int i = route.waypoints.length - 1; i >= 0; i--) {
                    long step = route.waypoints[i];
                    if (step == start) {
                        offset = i + 1;
                        onRoute = true;
                        break;
                    }
                    if (offset < 0 && manhattan(step, start) == 1L) offset = i;
                }
            }
            if (offset < 0 || offset >= route.waypoints.length) continue;
            boolean sameGoals = Arrays.equals(route.sortedGoals, currentGoals);
            if (route.complete && route.revision == revision && sameGoals && onRoute) {
                return new Candidate(route, true, offset);
            }
            // A neighbouring cell has no proof of optimality. Only warm the
            // normal A* when following this suffix progresses towards work.
            long distance = nearestManhattan(route.waypoints[route.waypoints.length - 1], currentGoals);
            if (distance >= startDistance) continue;
            long score = distance + (route.waypoints.length - offset) / 16L;
            if (score < bestScore) {
                best = new Candidate(route, false, offset);
                bestScore = score;
            }
        }
        return best;
    }

    public boolean isCurrent(long now, Route route) {
        if (route == null) return false;
        expire(now);
        for (Route entry : routes) if (entry == route) return true;
        return false;
    }

    /** Exact hits also need an unchanged world revision, including DURING validation. */
    public boolean mayShortCircuit(long now, Route route) {
        return isCurrent(now, route) && route.complete && route.revision == revision;
    }

    /** Invalid path witnesses are removed, but valid alternate routes survive. */
    public void discard(Route route) { routes.removeIf(value -> value == route); }

    /**
     * World mutations can create a shorter path. Invalidate direct hits even
     * when far from the corridor, but retain safe, revalidated A* warm starts.
     */
    public void worldChanged(long changedPosition) {
        revision++;
        // Mining the old destination must not discard the reusable corridor.
        // Its exact-route authority is already revoked by the revision bump.
        // Most mining updates are outside the few cached corridors. Test
        // precomputed bounds before inspecting up to 256 packed waypoints.
        routes.removeIf(route -> route.covers(changedPosition)
                && contains(route.waypoints, changedPosition));
    }

    public void clear() {
        routes.clear();
        revision++;
        lastTick = Long.MIN_VALUE;
    }

    public int entries() { return routes.size(); }

    private void expire(long now) {
        if (now < lastTick) clear();
        lastTick = now;
        routes.removeIf(route -> route.expiresAt <= now);
    }

    private static boolean contains(long[] positions, long value) {
        for (long packed : positions) if (packed == value) return true;
        return false;
    }

    private static long manhattan(long a, long b) {
        return Math.abs((long) x(a) - x(b)) + Math.abs((long) y(a) - y(b))
                + Math.abs((long) z(a) - z(b));
    }

    private static long nearestManhattan(long origin, long[] goals) {
        long closest = Long.MAX_VALUE;
        for (long target : goals) {
            long distance = Math.abs((long) x(origin) - x(target))
                    + Math.abs((long) y(origin) - y(target))
                    + Math.abs((long) z(origin) - z(target));
            closest = Math.min(closest, distance);
        }
        return closest;
    }

    private static int x(long packed) { return (int) (packed >> 38); }
    private static int y(long packed) { return (int) (packed << 52 >> 52); }
    private static int z(long packed) { return (int) (packed << 26 >> 38); }
}
