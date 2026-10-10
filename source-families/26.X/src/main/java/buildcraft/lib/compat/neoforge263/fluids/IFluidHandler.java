//? source if >=26.3
package buildcraft.lib.compat.neoforge263.fluids;

import net.neoforged.neoforge.fluids.FluidStack;

/** Legacy BCCE operations: conversions to native 26.3 transfer happen only at the adapter boundary. */
public interface IFluidHandler {
    int getTanks();
    FluidStack getFluidInTank(int tank);
    int getTankCapacity(int tank);
    boolean isFluidValid(int tank, FluidStack fluid);
    int fill(FluidStack fluid, FluidAction action);
    FluidStack drain(FluidStack fluid, FluidAction action);
    FluidStack drain(int maxDrain, FluidAction action);

    enum FluidAction {
        EXECUTE, SIMULATE;
        public boolean execute() { return this == EXECUTE; }
        public boolean simulate() { return this == SIMULATE; }
    }
}
