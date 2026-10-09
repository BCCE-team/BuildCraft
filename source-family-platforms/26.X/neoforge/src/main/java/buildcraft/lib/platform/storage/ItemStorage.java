package buildcraft.lib.platform.storage;

import net.minecraft.world.item.ItemStack;

/** Internal slot view. Insert returns the remainder; extract returns the extracted stack.
 * Simulation must not mutate storage. Returned slot stacks retain the backing handler's read-only semantics. */
public interface ItemStorage {
    int getSlots();
    ItemStack getStackInSlot(int slot);
    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);
    ItemStack extractItem(int slot, int amount, boolean simulate);
    int getSlotLimit(int slot);
    boolean isItemValid(int slot, ItemStack stack);
}
