package buildcraft.lib.internal.api.v2.energy;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjDemandPort;
import buildcraft.api.v2.energy.MjPassivePort;
import buildcraft.api.v2.energy.MjStorage;
import buildcraft.api.v2.energy.MjStoragePolicy;
import buildcraft.api.v2.energy.MjStorageState;
import buildcraft.api.v2.energy.MjTransferResult;
import java.util.Objects;

/** Synchronized, conservative MJ storage. Rate budgets count only EXECUTE transfers. */
public final class MjStorageImpl implements MjStorage, MjDemandPort, MjPassivePort {
    private final MjStoragePolicy policy;
    private long stored;
    private long insertedThisTick;
    private long extractedThisTick;
    private long lastTick = Long.MIN_VALUE;

    public MjStorageImpl(MjAmount capacity) { this(capacity, MjAmount.ZERO); }

    public MjStorageImpl(MjAmount capacity, MjAmount initial) {
        this(MjStoragePolicy.standard(capacity), initial);
    }

    public MjStorageImpl(MjStoragePolicy policy, MjAmount initial) {
        this.policy = Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(initial, "initial");
        if (initial.compareTo(policy.maximumStored()) > 0) {
            throw new IllegalArgumentException("stored MJ exceeds configured maximum");
        }
        this.stored = initial.microMj();
    }

    @Override public MjStoragePolicy policy() { return policy; }
    @Override public MjAmount capacity() { return policy.capacity(); }
    @Override public synchronized MjAmount stored() { return MjAmount.ofMicro(stored); }
    @Override public boolean canInsert() { return !policy.maxInsertPerTick().isZero(); }
    @Override public boolean canExtract() { return !policy.maxExtractPerTick().isZero(); }

    @Override
    public synchronized MjAmount requested() {
        return MjAmount.ofMicro(Math.min(policy.maximumStored().microMj() - stored,
            policy.maxInsertPerTick().microMj() - insertedThisTick));
    }

    @Override
    public synchronized MjTransferResult insert(MjAmount offered, OperationMode mode) {
        Objects.requireNonNull(offered, "offered");
        Objects.requireNonNull(mode, "mode");
        long accepted = Math.min(offered.microMj(), requested().microMj());
        if (mode == OperationMode.EXECUTE) {
            stored += accepted;
            insertedThisTick += accepted;
        }
        return MjTransferResult.of(offered, MjAmount.ofMicro(accepted));
    }

    @Override
    public synchronized MjTransferResult extract(MjAmount requested, OperationMode mode) {
        return extract(MjAmount.ZERO, requested, mode);
    }

    @Override
    public synchronized MjTransferResult extract(MjAmount minimum, MjAmount maximum, OperationMode mode) {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        Objects.requireNonNull(mode, "mode");
        if (minimum.compareTo(maximum) > 0) throw new IllegalArgumentException("minimum exceeds maximum");
        long amount = Math.min(maximum.microMj(), Math.min(stored,
            policy.maxExtractPerTick().microMj() - extractedThisTick));
        if (amount < minimum.microMj()) amount = 0;
        if (mode == OperationMode.EXECUTE) {
            stored -= amount;
            extractedThisTick += amount;
        }
        return MjTransferResult.of(maximum, MjAmount.ofMicro(amount));
    }

    @Override
    public synchronized MjStorageState snapshot() { return MjStorageState.fromMicroMj(stored); }

    @Override
    public synchronized void restore(MjStorageState state) {
        Objects.requireNonNull(state, "state");
        if (state.stored().compareTo(policy.maximumStored()) > 0) {
            throw new IllegalArgumentException("restored MJ exceeds maximum");
        }
        stored = state.microMj();
        insertedThisTick = 0;
        extractedThisTick = 0;
        lastTick = Long.MIN_VALUE;
    }

    @Override
    public synchronized MjAmount tick(long gameTime) {
        if (gameTime == lastTick) return MjAmount.ZERO;
        // One rate budget per tick; also resets safely if time moves backwards.
        insertedThisTick = extractedThisTick = 0;
        lastTick = gameTime;
        long nominal = policy.capacity().microMj();
        long threshold = nominal > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : nominal * 2;
        if (stored <= threshold) return MjAmount.ZERO;
        long difference = stored - threshold;
        long lost = difference / 32 + (difference % 32 == 0 ? 0 : 1);
        stored -= lost;
        return MjAmount.ofMicro(lost);
    }
}
