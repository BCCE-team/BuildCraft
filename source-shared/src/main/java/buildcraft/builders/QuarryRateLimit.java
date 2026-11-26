package buildcraft.builders;

/** Converts a configured physical rate to a per-tick budget for task progress. */
public final class QuarryRateLimit {
    private QuarryRateLimit() {}

    public static long progress(long required, long target, double taskUnits, double unitsPerSecond, double alreadyUsed) {
        if (required <= 0) return 0;
        if (unitsPerSecond <= 0 || taskUnits <= 0 || target <= 0) return required;
        double remainingUnits = unitsPerSecond / 20.0 - alreadyUsed;
        if (remainingUnits <= 0) return 0;
        // Round down to avoid exceeding the limit, except the minimum representable micro-MJ of progress.
        double limit = target * (remainingUnits / taskUnits);
        return Math.min(required, Math.max(1L, (long) limit));
    }
}
