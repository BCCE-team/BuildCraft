package buildcraft.robotics.scheduler;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * Transient, panel-scoped hints from completed, unsuccessful miner A* searches.
 *
 * <p>An unsuccessful <em>bounded</em> search is not proof that an ore is
 * globally unreachable. Never delete catalog entries or assign a persistent
 * "unreachable" flag based on these hints. A different starting area may
 * provide a valid route. Only another robot starting at a passable position
 * actually discovered by the first search may reuse its temporary backoff.</p>
 *
 * <p>World mutations invalidate the whole cache: a newly opened wall can
 * invalidate every recorded failure. Expiration covers changes from mods that
 * do not call the processor's block-change hook.</p>
 */
public final class RobotSharedRouteFailures {
    private static final int MAX_FAILURES = 32;
    private static final int MAX_GOALS_PER_FAILURE = 64;
    // A 13x13x13 cube holds at most 2197 cells. Keeping all of them avoids
    // arbitrary HashMap iteration dropping the second miner's nearby start.
    private static final int MAX_START_WITNESSES = 2300;
    private static final int START_WITNESS_RADIUS = 6;
    private static final long EXHAUSTED_TTL = 200L;
    private static final long NODE_LIMIT_TTL = 60L;

    private record FailedSearch(Set<Long> goals, Set<Long> reachedStarts,
                                long expiresAt, boolean exhausted) { }

    private final ArrayDeque<FailedSearch> failures = new ArrayDeque<>();
    private final Map<Long, ArrayDeque<FailedSearch>> byGoal = new HashMap<>();

    /** Return the number of actual ore positions recorded, or zero. */
    public int remember(BlockPos origin, Collection<BlockPos> goals,
                        Collection<BlockPos> discoveredAir, long tick, boolean exhausted) {
        if (origin == null || goals == null || goals.isEmpty() || discoveredAir == null) return 0;
        expire(tick);
        Set<Long> relevantGoals = new HashSet<>();
        for (BlockPos goal : goals) {
            if (goal != null) relevantGoals.add(goal.asLong());
            if (relevantGoals.size() >= MAX_GOALS_PER_FAILURE) break;
        }
        if (relevantGoals.isEmpty()) return 0;
        Set<Long> sameApproach = new HashSet<>();
        sameApproach.add(origin.asLong());
        for (BlockPos visited : discoveredAir) {
            if (visited == null || !nearby(origin, visited)) continue;
            sameApproach.add(visited.asLong());
            if (sameApproach.size() >= MAX_START_WITNESSES) break;
        }
        long duration = exhausted ? EXHAUSTED_TTL : NODE_LIMIT_TTL;
        FailedSearch failure = new FailedSearch(Set.copyOf(relevantGoals),
                Set.copyOf(sameApproach), tick + duration, exhausted);
        if (failures.size() >= MAX_FAILURES) removeOldest();
        failures.addLast(failure);
        for (long goal : failure.goals()) {
            byGoal.computeIfAbsent(goal, unused -> new ArrayDeque<>()).addLast(failure);
        }
        return relevantGoals.size();
    }

    /** True only for a specific goal from the same actually explored approach. */
    public boolean shouldDefer(BlockPos currentStart, long goal, long now) {
        if (currentStart == null) return false;
        ArrayDeque<FailedSearch> candidates = byGoal.get(goal);
        if (candidates == null) return false;
        long packedStart = currentStart.asLong();
        for (FailedSearch failure : candidates) {
            if (failure.expiresAt() > now && failure.reachedStarts().contains(packedStart)) return true;
        }
        return false;
    }

    /**
     * A queued search is deferred ONLY if every one of its targets has a
     * fresh failure witness for this actual starting cell. One new, untested
     * goal immediately permits the normal A* search.
     *
     * @return the first expiration, or zero if normal search should proceed
     */
    public long deferUntil(BlockPos start, Collection<BlockPos> goals, long now) {
        if (start == null || goals == null || goals.isEmpty()) return 0L;
        long firstExpiry = Long.MAX_VALUE;
        long packedStart = start.asLong();
        for (BlockPos goal : goals) {
            if (goal == null) return 0L;
            ArrayDeque<FailedSearch> candidates = byGoal.get(goal.asLong());
            if (candidates == null) return 0L;
            long latest = 0L;
            for (FailedSearch failure : candidates) {
                if (failure.expiresAt() > now && failure.reachedStarts().contains(packedStart)) {
                    latest = Math.max(latest, failure.expiresAt());
                }
            }
            if (latest == 0L) return 0L;
            firstExpiry = Math.min(firstExpiry, latest);
        }
        return firstExpiry == Long.MAX_VALUE ? 0L : firstExpiry;
    }

    public void expire(long now) {
        for (Iterator<FailedSearch> iterator = failures.iterator(); iterator.hasNext();) {
            FailedSearch failure = iterator.next();
            if (failure.expiresAt() <= now) {
                iterator.remove();
                unlink(failure);
            }
        }
    }

    /** Any unknown block update may open a passage, including a diagonal one. */
    public void worldChanged() {
        failures.clear();
        byGoal.clear();
    }

    public int cachedSearches() { return failures.size(); }

    private void removeOldest() {
        FailedSearch first = failures.pollFirst();
        if (first != null) unlink(first);
    }

    private void unlink(FailedSearch failure) {
        for (long goal : failure.goals()) {
            ArrayDeque<FailedSearch> entries = byGoal.get(goal);
            if (entries != null) {
                entries.remove(failure);
                if (entries.isEmpty()) byGoal.remove(goal);
            }
        }
    }

    private static boolean nearby(BlockPos start, BlockPos position) {
        long dx = (long) start.getX() - position.getX();
        long dy = (long) start.getY() - position.getY();
        long dz = (long) start.getZ() - position.getZ();
        return Math.abs(dx) <= START_WITNESS_RADIUS && Math.abs(dy) <= START_WITNESS_RADIUS
                && Math.abs(dz) <= START_WITNESS_RADIUS;
    }
}
