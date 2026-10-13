package buildcraft.api.v2.energy;

/**
 * Mutable MJ buffer whose implementation is obtained from {@link EnergyService}.
 * Addons must call {@link EnergyService#tickStorage} once per server tick when using
 * per-tick rate limits or the optional BC8 overcharge decay effect.
 */
public interface MjStorage extends MjPort {
    @Override MjAmount capacity();
    @Override MjAmount stored();

    default MjStoragePolicy policy() { return MjStoragePolicy.standard(capacity()); }

    /** Transport-independent state suitable for saving as a long in the owner's persistent data. */
    default MjStorageState snapshot() { return new MjStorageState(stored()); }

    /** Restore a state saved with {@link #snapshot}; implementations enforce their configured hard limit. */
    default void restore(MjStorageState state) {
        throw new UnsupportedOperationException("MJ storage does not support persistence");
    }

    /** Start the given logical tick and apply overcharge decay; returns the micro-MJ actually lost. */
    default MjAmount tick(long gameTime) { return MjAmount.ZERO; }
}
