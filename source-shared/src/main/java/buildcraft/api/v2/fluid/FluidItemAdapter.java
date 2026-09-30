package buildcraft.api.v2.fluid;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * Adapter for items that represent a fluid without requiring the item to expose
 * a loader-specific fluid capability.
 */
public interface FluidItemAdapter {
    boolean supports(ItemStack stack);

    FluidVolume fluid(ItemStack stack);

    default FluidVolume requireFluid(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (!supports(stack)) {
            throw new IllegalArgumentException("Unsupported item stack");
        }
        return Objects.requireNonNull(fluid(stack), "fluid(stack)");
    }
}