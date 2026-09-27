package buildcraft.lib.platform.storage;

/**
 * Internal <em>tank-indexed</em> fluid storage view.
 *
 * <p>{@code F} preserves the existing fluid/components carrier losslessly. Fill returns accepted volume;
 * drain returns extracted fluid, and amounts remain millibuckets. This contract is intentionally reserved for
 * storages that expose stable tank indices. Transaction-native or dynamically-viewed native storages (for example a
 * generic Fabric {@code Storage<FluidVariant>}) must stay on the slotless {@code FluidTransferAccess} boundary rather
 * than fabricating tank numbers from an iterator.</p>
 *
 * <p>No conversion to a public API fluid representation or native transaction bypass is performed here.</p>
 */
public interface FluidStorage<F> {
    int getTanks();
    F getFluidInTank(int tank);
    int getTankCapacity(int tank);
    boolean isFluidValid(int tank, F fluid);
    int fill(F fluid, boolean simulate);
    F drain(F fluid, boolean simulate);
    F drain(int amount, boolean simulate);
}
