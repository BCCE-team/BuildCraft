package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;

/** Internal loader-neutral fluid-transfer endpoint. */
public interface FluidTransferAccess {
    FluidTransferResult insert(FluidVolume offered, OperationScope scope);
    FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationScope scope);
}
