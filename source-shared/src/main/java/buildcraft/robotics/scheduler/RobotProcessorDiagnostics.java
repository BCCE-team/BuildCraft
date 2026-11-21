package buildcraft.robotics.scheduler;

/**
 * Transient, allocation-free-in-the-hot-path telemetry for one robot processor.
 * Counts are taken on the server thread; a GUI reads only the last completed
 * twenty-tick window. No world/chunk/entity references or save-data writes.
 *
 * Values describe actual inspected blocks and measured scheduler CPU time,
 * not invented TPS or estimated block counts from scan progress.
 */
public final class RobotProcessorDiagnostics {
    public static final int WINDOW_TICKS = 20;

    public record Snapshot(int inspected, int dirty, int focused, int background,
                           int skippedSections, int budgetLimitedTicks,
                           int cachedCandidates, int categories, int linkedRobots,
                           int loadedChunks, int averageMicros, int peakMicros) {
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0);
    }

    private long windowStart = Long.MIN_VALUE;
    private int ticks;
    private int inspected;
    private int dirty;
    private int focused;
    private int background;
    private int skippedSections;
    private int budgetLimitedTicks;
    private long cpuNanos;
    private long peakNanos;
    private Snapshot completed = Snapshot.EMPTY;

    /** Called once before the panel's tick, including when no robot is ready. */
    public void beginTick(long tick) {
        if (windowStart == Long.MIN_VALUE) {
            windowStart = tick;
        } else if (tick < windowStart || tick - windowStart >= WINDOW_TICKS) {
            // A world time jump or a long unload does not stretch a 20t
            // sample over hours; the last completed window stays readable.
            if (ticks > 0) publish(0, 0, 0, 0);
            clearCounters();
            windowStart = tick;
        }
    }

    public void inspected() { inspected++; }
    int inspectedSoFar() { return inspected; }
    public void dirty() { dirty++; }
    public void focused() { focused++; }
    public void background() { background++; }
    public void skippedSection() { skippedSections++; }
    public void budgetLimited() { budgetLimitedTicks++; }

    /** Only counters and metadata, never a chunk scan or an index snapshot. */
    public void finishTick(long nanos) {
        ticks++;
        long elapsed = Math.max(0L, nanos);
        cpuNanos = elapsed > Long.MAX_VALUE - cpuNanos ? Long.MAX_VALUE : cpuNanos + elapsed;
        peakNanos = Math.max(peakNanos, elapsed);
    }

    public Snapshot snapshot(int candidates, int categoryCount, int linked, int chunks) {
        // The metadata comes from already maintained in-memory structures;
        // keep it fresh even when a 20-tick CPU window has not finished.
        return new Snapshot(completed.inspected(), completed.dirty(), completed.focused(),
                completed.background(), completed.skippedSections(), completed.budgetLimitedTicks(),
                Math.max(0, candidates), Math.max(0, categoryCount), Math.max(0, linked),
                Math.max(0, chunks), completed.averageMicros(), completed.peakMicros());
    }

    public void reset() {
        windowStart = Long.MIN_VALUE;
        completed = Snapshot.EMPTY;
        clearCounters();
    }

    private void publish(int candidates, int categoryCount, int linked, int chunks) {
        completed = new Snapshot(inspected, dirty, focused, background,
                skippedSections, budgetLimitedTicks, Math.max(0, candidates),
                Math.max(0, categoryCount), Math.max(0, linked), Math.max(0, chunks),
                toMicros(ticks == 0 ? 0 : cpuNanos / ticks), toMicros(peakNanos));
    }

    private static int toMicros(long nanos) {
        // The menu validates the wire range. A pathological stuck tick must
        // not disconnect players merely because it exceeded that cap.
        return (int) Math.min(10_000_000L, Math.max(0L, nanos / 1_000L));
    }

    private void clearCounters() {
        ticks = inspected = dirty = focused = background = skippedSections = budgetLimitedTicks = 0;
        cpuNanos = peakNanos = 0L;
    }
}
