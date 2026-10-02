package buildcraft.lib.platform.storage;

/** Internal fluid operations. F preserves the existing fluid/components carrier losslessly.
 * Fill returns accepted volume; drain returns extracted fluid. Amounts remain millibuckets.
 * No conversion to a public API fluid representation or native transaction bypass is performed. */
public interface FluidStorage<F> {
    int getTanks();
    F getFluidInTank(int tank);
    int getTankCapacity(int tank);
    boolean isFluidValid(int tank, F fluid);
    int fill(F fluid, boolean simulate);
    F drain(F fluid, boolean simulate);
    F drain(int amount, boolean simulate);
}
