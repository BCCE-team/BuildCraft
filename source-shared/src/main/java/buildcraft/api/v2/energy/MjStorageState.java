package buildcraft.api.v2.energy;

import java.util.Objects;

/** Persist only the physical energy amount; per-tick budgets restart after world reload. */
public record MjStorageState(MjAmount stored) {
    public MjStorageState {
        Objects.requireNonNull(stored, "stored");
    }

    public static MjStorageState fromMicroMj(long stored) {
        return new MjStorageState(MjAmount.ofMicro(stored));
    }

    public long microMj() { return stored.microMj(); }
}
