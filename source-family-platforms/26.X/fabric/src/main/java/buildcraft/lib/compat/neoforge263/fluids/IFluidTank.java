package buildcraft.lib.compat.neoforge263.fluids;

import buildcraft.lib.fluid.FluidStack;

public interface IFluidTank {
    FluidStack getFluid();
    int getFluidAmount();
    int getCapacity();
    boolean isFluidValid(FluidStack stack);
    int fill(FluidStack stack, IFluidHandler.FluidAction action);
    FluidStack drain(int maxDrain, IFluidHandler.FluidAction action);
}
