package buildcraft.robotics.scheduler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Transient, dimension-scoped arbitration between robots waiting for charging docks.
 *
 * <p>This is an advisory waiting queue, not a second physical station reservation.
 * DockingStation.take() remains the final, atomic authority. A request contains
 * only immutable station identities, never a chunk, station, or robot reference.
 * Expired or cancelled requests cannot keep a dock unavailable indefinitely.</p>
 *
 * <p>All methods are called from the Minecraft server thread. Each dimension
 * owns a distinct instance; nothing in this class loads chunks or persists
 * reservations across server restarts.</p>
 *
 * @param <S> the identity of a station (including its mounted side)
 */
public final class ChargingDockQueue<S> {
    public static final long REQUEST_TIMEOUT_TICKS = 80;
    private static final long MAX_AGE_BONUS = 800;

    private final Map<Long, Request<S>> waiting = new HashMap<>();
    /** Inverted station index avoids scanning every robot for each dock candidate. */
    private final Map<S, Set<Long>> byStation = new HashMap<>();
    private long lastCleanupTick = Long.MIN_VALUE;

    /**
     * Register or refresh demand. {@code eligible} must contain only stations
     * that the robot is actually allowed to use (including currently occupied
     * ones), not every station registered in the dimension.
     *
     * @param urgency 0 = ordinary recharge, 1 = low energy, 2 = critical energy
     */
    public void register(long robotId, int urgency, long tick, Set<S> eligible) {
        cleanup(tick);
        if (eligible == null || eligible.isEmpty()) {
            cancel(robotId);
            return;
        }
        Request<S> request = waiting.get(robotId);
        if (request == null) {
            request = new Request<>(tick);
            waiting.put(robotId, request);
        } else {
            unindex(robotId, request.eligible);
        }
        request.lastSeen = tick;
        request.urgency = Math.max(0, Math.min(2, urgency));
        request.eligible = Set.copyOf(new HashSet<>(eligible));
        for (S station : request.eligible) {
            byStation.computeIfAbsent(station, ignored -> new HashSet<>()).add(robotId);
        }
    }

    /**
     * May this robot try to take the now-free dock? Longer-waiting contenders
     * have priority, with a bounded bonus for critically low battery. A very
     * old ordinary request eventually outranks a newly-arrived critical one,
     * avoiding permanent starvation when many robots need charging.
     *
     * <p>False is a temporary wait, never a route failure. The caller must
     * still invoke DockingStation.take() even when this returns true.</p>
     */
    public boolean maySelect(long robotId, S station, long tick) {
        cleanup(tick);
        Request<S> current = waiting.get(robotId);
        if (current == null || !current.eligible.contains(station)) return true;
        for (long contenderId : byStation.getOrDefault(station, Set.of())) {
            if (contenderId == robotId) continue;
            Request<S> other = waiting.get(contenderId);
            if (other != null && higherPriority(other, contenderId, current, robotId, tick)) return false;
        }
        return true;
    }

    /** Clear an assignment on successful selection, interruption, or unload. */
    public void cancel(long robotId) {
        Request<S> previous = waiting.remove(robotId);
        if (previous != null) unindex(robotId, previous.eligible);
    }

    private void unindex(long robotId, Set<S> stations) {
        for (S station : stations) {
            Set<Long> indexed = byStation.get(station);
            if (indexed == null) continue;
            indexed.remove(robotId);
            if (indexed.isEmpty()) byStation.remove(station);
        }
    }

    public int waitingCount(long tick) {
        cleanup(tick);
        return waiting.size();
    }

    private static <S> boolean higherPriority(Request<S> a, long aId,
                                              Request<S> b, long bId, long tick) {
        long aScore = score(a, tick);
        long bScore = score(b, tick);
        if (aScore != bScore) return aScore > bScore;
        if (a.since != b.since) return a.since < b.since;
        return aId < bId;
    }

    private static <S> long score(Request<S> request, long tick) {
        long elapsed = Math.max(0L, tick - request.since);
        return request.urgency * 150L + Math.min(MAX_AGE_BONUS, elapsed / 4L);
    }

    private void cleanup(long tick) {
        if (lastCleanupTick == tick) return;
        lastCleanupTick = tick;
        Iterator<Map.Entry<Long, Request<S>>> it = waiting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Request<S>> entry = it.next();
            Request<S> request = entry.getValue();
            if (tick < request.lastSeen || tick - request.lastSeen > REQUEST_TIMEOUT_TICKS) {
                unindex(entry.getKey(), request.eligible);
                it.remove();
            }
        }
    }

    private static final class Request<S> {
        final long since;
        long lastSeen;
        int urgency;
        Set<S> eligible = Set.of();

        Request(long tick) {
            since = tick;
            lastSeen = tick;
        }
    }
}
