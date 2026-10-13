package buildcraft.api.v2.energy;

import java.util.Objects;

/** Immutable MJ buffer configuration. Limits count micro-MJ moved during one logical tick. */
public record MjStoragePolicy(
    MjAmount capacity,
    MjAmount maximumStored,
    MjAmount maxInsertPerTick,
    MjAmount maxExtractPerTick,
    boolean passiveProvider,
    boolean redstoneReceiver
) {
    public MjStoragePolicy {
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(maximumStored, "maximumStored");
        Objects.requireNonNull(maxInsertPerTick, "maxInsertPerTick");
        Objects.requireNonNull(maxExtractPerTick, "maxExtractPerTick");
        if (maximumStored.compareTo(capacity) < 0) {
            throw new IllegalArgumentException("maximumStored must be >= nominal capacity");
        }
    }

    public static MjStoragePolicy standard(MjAmount capacity) {
        return new MjStoragePolicy(capacity, capacity,
            MjAmount.ofMicro(Long.MAX_VALUE), MjAmount.ofMicro(Long.MAX_VALUE), true, false);
    }
}
