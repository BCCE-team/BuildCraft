/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.transport.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Visual playout for item cargoes. The server announces the actual start tick of
 * each half-pipe movement; packet arrival time is NOT a movement start event.
 *
 * <p>A single estimated server-to-client clock offset is sampled from packet
 * send ticks. Every cargo freezes its offset on its first packet, preventing
 * packet jitter from adding a pause at each subsequent gold-pipe transition.
 * The clock sample is based on the earliest observed arrival (minimum network
 * delay), not on the latest possibly delayed packet. Each cargo keeps its own
 * fixed offset while it is on screen, so updating the estimate cannot snap an
 * existing stream. Positions are evaluated with partial ticks in the renderer.
 *
 * <p>Legacy addon packets without authoritative timestamps use the old
 * relative, predecessor-chained path for backwards source compatibility.</p>
 */
public final class ClientItemSegmentTimeline<T> {
    /** 150 ms of initial latency at 20 TPS; never added again at a handoff. */
    public static final int PLAYOUT_BUFFER_TICKS = 3;
    /** At most one server tick of a newly announced movement may be played
     * retroactively. Older data must queue instead of fast-forwarding: at the
     * gold-pipe cadence a catch-up burst otherwise skips several whole pipes. */
    public static final int MAX_LATE_BACKFILL_TICKS = 1;
    /** Keep a completed segment for bounded extrapolation if a packet is late. */
    public static final int TAIL_TICKS = 6;
    public static final long NO_SERVER_TICK = Long.MIN_VALUE;
    private static final int MAX_TRACKED_CARGO = 8192;
    private static final int MAX_SEGMENTS_PER_CARGO = 24;
    private static final int PRUNE_INTERVAL_TICKS = 20;
    /** A cargo can briefly wait at a machine or chunk boundary after its
     * previous segment has expired. Keep its clock mapping across that wait. */
    private static final int TRACK_CLOCK_RETENTION_TICKS = 40;

    private final LinkedHashMap<Long, Track<T>> tracks = new LinkedHashMap<>();
    private long lastPruneTick = Long.MIN_VALUE;
    private boolean hasClockSample;
    private long minimumArrivalOffset;

    private static final class Segment<T> {
        final T item;
        final long start;
        final long finish;

        Segment(T item, long start, long finish) {
            this.item = item;
            this.start = start;
            this.finish = finish;
        }
    }

    private static final class Track<T> {
        final ArrayDeque<Segment<T>> segments = new ArrayDeque<>();
        long serverToClientOffset;
        boolean hasServerOffset;
        long lastFinish = Long.MIN_VALUE;
    }

    /**
     * Translate one authoritative server start into the local monotonic clock.
     * The first packet sets an offset for this individual cargo. Subsequent
     * packets use exactly the same offset, regardless of delivery jitter.
     */
    public long startFor(long visualId, long now, long serverStart, long serverSent) {
        if (visualId <= 0L || serverStart == NO_SERVER_TICK || serverSent == NO_SERVER_TICK) {
            return startFor(visualId, now);
        }
        long sample = now - serverSent;
        if (!hasClockSample || sample < minimumArrivalOffset) {
            minimumArrivalOffset = sample;
            hasClockSample = true;
        }
        Track<T> track = tracks.computeIfAbsent(visualId, ignored -> new Track<>());
        if (!track.hasServerOffset) {
            track.serverToClientOffset = minimumArrivalOffset + PLAYOUT_BUFFER_TICKS;
            track.hasServerOffset = true;
        }
        long desiredStart = serverStart + track.serverToClientOffset;
        Segment<T> previous = track.segments.peekLast();
        final long start;
        if (previous == null) {
            // First packet for a cargo: never start in the past. Packets that
            // took longer than the jitter budget must not teleport the item
            // several pipes forward as soon as they arrive.
            start = Math.max(desiredStart, now + PLAYOUT_BUFFER_TICKS);
        } else {
            // A late packet may describe a half-pipe that the server has
            // already finished. Preserve the predecessor's finish and let the
            // client play each announced segment at its original duration.
            // Only one tick of backfill is allowed for a predictable straight
            // handoff; the rest is buffered instead of replayed at bullet speed.
            start = Math.max(Math.max(desiredStart, previous.finish),
                now - MAX_LATE_BACKFILL_TICKS);
        }
        if (start > desiredStart) {
            // Retiming a late segment without moving the cargo's clock offset
            // would make the *next* packet try to catch up all over again.
            track.serverToClientOffset += start - desiredStart;
        }
        return start;
    }

    /** Fallback for pre-timestamp addon movement notifications. */
    public long startFor(long visualId, long now) {
        if (visualId > 0L) {
            Track<T> track = tracks.get(visualId);
            Segment<T> previous = track == null ? null : track.segments.peekLast();
            if (previous != null && previous.finish + TAIL_TICKS >= now) {
                return previous.finish;
            }
        }
        return now + PLAYOUT_BUFFER_TICKS;
    }

    /**
     * Keep the sequence sorted by *server* movement starts. Chunk tracking and
     * packet batching do not guarantee that movement events are received in
     * their original order across a reconnect/full-state snapshot. Sorting is
     * needed so older events never replace a newer active movement.
     */
    public void record(long visualId, T item, long start, long finish, long now) {
        if (visualId <= 0L) return;
        Track<T> track = tracks.computeIfAbsent(visualId, ignored -> new Track<>());
        Segment<T> segment = new Segment<>(item, start, finish);
        track.lastFinish = Math.max(track.lastFinish, finish);
        Segment<T> last = track.segments.peekLast();
        if (last == null || last.start <= start) {
            track.segments.addLast(segment);
        } else {
            List<Segment<T>> ordered = new ArrayList<>(track.segments);
            int position = 0;
            while (position < ordered.size() && ordered.get(position).start <= start) position++;
            ordered.add(position, segment);
            track.segments.clear();
            track.segments.addAll(ordered);
        }
        while (track.segments.size() > MAX_SEGMENTS_PER_CARGO) track.segments.removeFirst();
        prune(now);
    }

    /** Exactly one segment of each identified cargo is eligible each frame. */
    public boolean visible(long visualId, T item, double renderTime, long start) {
        if (renderTime < start) return false;
        if (visualId <= 0L) return true;
        Track<T> track = tracks.get(visualId);
        if (track == null) return true;
        Segment<T> active = null;
        for (Segment<T> segment : track.segments) {
            if (renderTime < segment.start) break;
            active = segment;
        }
        return active != null && active.item == item;
    }

    /** Bounded memory for continuously operating quarries and long worlds. */
    public void prune(long now) {
        if (lastPruneTick != Long.MIN_VALUE
                && now - lastPruneTick < PRUNE_INTERVAL_TICKS
                && tracks.size() <= MAX_TRACKED_CARGO) return;
        lastPruneTick = now;
        Iterator<Map.Entry<Long, Track<T>>> iterator = tracks.entrySet().iterator();
        while (iterator.hasNext()) {
            Track<T> track = iterator.next().getValue();
            while (!track.segments.isEmpty()
                    && track.segments.peekFirst().finish + TAIL_TICKS < now) {
                track.segments.removeFirst();
            }
            if (track.segments.isEmpty()
                    && now > track.lastFinish + TRACK_CLOCK_RETENTION_TICKS) iterator.remove();
        }
        while (tracks.size() > MAX_TRACKED_CARGO) {
            Iterator<Long> oldest = tracks.keySet().iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }

    public int trackedCount() { return tracks.size(); }
}
