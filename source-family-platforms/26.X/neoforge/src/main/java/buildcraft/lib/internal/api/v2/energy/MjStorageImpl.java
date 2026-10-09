package buildcraft.lib.internal.api.v2.energy;

import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.api.v2.energy.MjStorage;

import buildcraft.api.v2.OperationMode;
import java.util.Objects;

/** Validated MJ storage primitive suitable for machines, engines and pipes. */
public final class MjStorageImpl implements MjStorage {
    private final MjAmount capacity;
    private MjAmount stored;

    public MjStorageImpl(MjAmount capacity) {
        this(capacity, MjAmount.ZERO);
    }

    public MjStorageImpl(MjAmount capacity, MjAmount stored) {
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.stored = Objects.requireNonNull(stored, "stored");
        if (stored.compareTo(capacity) > 0) throw new IllegalArgumentException("stored MJ exceeds capacity");
    }

    public synchronized MjAmount capacity() { return capacity; }
    public synchronized MjAmount stored() { return stored; }

    public synchronized MjTransferResult insert(MjAmount offered, OperationMode mode) {
        Objects.requireNonNull(offered, "offered"); Objects.requireNonNull(mode, "mode");
        long room = capacity.microMj() - stored.microMj();
        long moved = Math.min(room, offered.microMj());
        if (mode == OperationMode.EXECUTE && moved != 0) stored = MjAmount.ofMicro(stored.microMj() + moved);
        return MjTransferResult.of(offered, MjAmount.ofMicro(moved));
    }

    public synchronized MjTransferResult extract(MjAmount requested, OperationMode mode) {
        Objects.requireNonNull(requested, "requested"); Objects.requireNonNull(mode, "mode");
        long moved = Math.min(stored.microMj(), requested.microMj());
        if (mode == OperationMode.EXECUTE && moved != 0) stored = MjAmount.ofMicro(stored.microMj() - moved);
        return MjTransferResult.of(requested, MjAmount.ofMicro(moved));
    }
}
