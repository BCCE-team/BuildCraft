package buildcraft.api.v2.energy;

import buildcraft.api.v2.OperationMode;

/** BC8 passive power provider: extraction may require a minimum amount. */
public interface MjPassivePort extends MjPort {
    /**
     * Atomically extracts either zero or an amount between minimum and maximum.
     * The implementation MUST leave its state unchanged when the minimum cannot be met.
     */
    @Override
    MjTransferResult extract(MjAmount minimum, MjAmount maximum, OperationMode mode);
}
