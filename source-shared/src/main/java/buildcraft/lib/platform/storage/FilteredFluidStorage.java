package buildcraft.lib.platform.storage;
import java.util.function.Predicate;

/** Optional native filtered extraction; absence must preserve the ordinary per-tank fallback. */
public interface FilteredFluidStorage<F> extends FluidStorage<F> {
    F drain(Predicate<F> filter, int amount, boolean simulate);
}
