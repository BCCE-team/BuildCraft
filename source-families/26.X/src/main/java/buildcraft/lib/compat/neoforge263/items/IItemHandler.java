//? source if >=26.3
package buildcraft.lib.compat.neoforge263.items;

import net.minecraft.world.item.ItemStack;

/** Internal BuildCraft 26.3 compatibility view; the public API is ResourceHandler<ItemResource>. */
public interface IItemHandler {
    int getSlots();
    ItemStack getStackInSlot(int slot);
    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);
    ItemStack extractItem(int slot, int amount, boolean simulate);
    int getSlotLimit(int slot);
    boolean isItemValid(int slot, ItemStack stack);
}
