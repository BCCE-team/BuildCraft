package buildcraft.api.v2.fluid;

import net.minecraft.world.item.ItemStack;

/**
 * Resolves the fluid represented by an item stack without exposing
 * loader-specific fluid stack types.
 */
public interface FluidItemService {
    FluidVolume fluid(ItemStack stack);
}