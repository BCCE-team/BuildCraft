//? source if >=26.3
package buildcraft.lib.compat.neoforge263.items.wrapper;

import buildcraft.lib.compat.neoforge263.items.IItemHandlerModifiable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Vanilla inventory view for BuildCraft's legacy internal storage API. */
public final class InvWrapper implements IItemHandlerModifiable {
    private final Container inventory;
    public InvWrapper(Container inventory) { this.inventory = java.util.Objects.requireNonNull(inventory); }
    @Override public int getSlots() { return inventory.getContainerSize(); }
    @Override public ItemStack getStackInSlot(int slot) { return inventory.getItem(slot); }
    @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !inventory.canPlaceItem(slot, stack)) return stack;
        ItemStack stored = inventory.getItem(slot);
        if (!stored.isEmpty() && !ItemStack.isSameItemSameComponents(stored, stack)) return stack;
        int capacity = Math.min(inventory.getMaxStackSize(stack), stack.getMaxStackSize());
        int moved = Math.min(stack.getCount(), capacity - stored.getCount());
        if (moved <= 0) return stack;
        if (!simulate) {
            if (stored.isEmpty()) inventory.setItem(slot, stack.copyWithCount(moved));
            else { stored.grow(moved); inventory.setChanged(); }
        }
        return stack.copyWithCount(stack.getCount() - moved);
    }
    @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack stored = inventory.getItem(slot);
        if (stored.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        int moved = Math.min(stored.getCount(), amount);
        if (simulate) return stored.copyWithCount(moved);
        ItemStack result = inventory.removeItem(slot, moved);
        inventory.setChanged();
        return result;
    }
    @Override public int getSlotLimit(int slot) { return inventory.getMaxStackSize(inventory.getItem(slot)); }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return inventory.canPlaceItem(slot, stack); }
    @Override public void setStackInSlot(int slot, ItemStack stack) { inventory.setItem(slot, stack); inventory.setChanged(); }
}
