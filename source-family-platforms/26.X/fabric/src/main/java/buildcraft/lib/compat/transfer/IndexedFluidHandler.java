package buildcraft.lib.compat.transfer;

import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;

/** Indexed native operations must never silently fill/drain a DIFFERENT legacy tank. */
public interface IndexedFluidHandler {
    int fillTank(int tank, FluidStack resource, FluidAction action);
    FluidStack drainTank(int tank, FluidStack resource, FluidAction action);
}
