package buildcraft.robotics.scheduler;

/**
 * Smoothed real scan CPU cost, updated once per panel tick (not per block).
 * Blocks read during special, non-scanning dispatchers are intentionally not
 * attributed to the common scanner. Values never leave the server or NBT.
 */
public final class RobotScanCostEstimator {
    public static final int BASELINE_NANOS_PER_BLOCK = 12_000;
    private static final int MIN_SAMPLE_BLOCKS = 8;
    private int nanosPerBlock = BASELINE_NANOS_PER_BLOCK;

    public int nanosPerBlock() { return nanosPerBlock; }

    public void sample(long elapsedNanos, int inspected) {
        if (inspected < MIN_SAMPLE_BLOCKS || elapsedNanos <= 0) return;
        long average = Math.max(1_000L, Math.min(250_000L, elapsedNanos / inspected));
        nanosPerBlock = (int) ((7L * nanosPerBlock + average) / 8L);
    }

    public void reset() { nanosPerBlock = BASELINE_NANOS_PER_BLOCK; }
}
