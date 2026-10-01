//? source if >=1.21.11
package buildcraft.lib.compat.transfer;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Indexed native operations must never silently fill/drain a DIFFERENT legacy tank. */
public interface IndexedFluidHandler {
    int fillTank(int tank, FluidStack resource, FluidAction action);
    FluidStack drainTank(int tank, FluidStack resource, FluidAction action);
}
