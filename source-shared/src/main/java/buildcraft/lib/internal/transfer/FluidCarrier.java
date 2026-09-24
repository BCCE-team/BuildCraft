package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.fluid.FluidVolume;

/** Lossless native-fluid carrier operations supplied by a loader adapter. */
public interface FluidCarrier<F> {
    boolean isEmpty(F stack);
    int amount(F stack);
    FluidVolume toVolume(F stack);
    F fromVolume(FluidVolume volume);
    F copyWithAmount(F stack, int amount);
}
