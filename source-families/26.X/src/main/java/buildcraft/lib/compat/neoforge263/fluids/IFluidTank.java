//? source if >=26.3
package buildcraft.lib.compat.neoforge263.fluids;

import net.neoforged.neoforge.fluids.FluidStack;

public interface IFluidTank {
    FluidStack getFluid();
    int getFluidAmount();
    int getCapacity();
    boolean isFluidValid(FluidStack stack);
    int fill(FluidStack stack, IFluidHandler.FluidAction action);
    FluidStack drain(int maxDrain, IFluidHandler.FluidAction action);
}
