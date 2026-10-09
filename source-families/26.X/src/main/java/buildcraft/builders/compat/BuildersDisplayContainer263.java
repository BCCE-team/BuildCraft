//? source if >=26.3
package buildcraft.builders.compat;

import buildcraft.lib.gui.ItemProvider;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Read-only vanilla slot view for the Builder's required-material preview. */
public final class BuildersDisplayContainer263 implements Container {
    private final ItemProvider provider;

    public BuildersDisplayContainer263(ItemProvider provider) { this.provider = provider; }

    @Override
    public int getContainerSize() { return provider.getSlots(); }

    @Override
    public boolean isEmpty() {
        for (int index = 0; index < getContainerSize(); index++) if (!getItem(index).isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        return index >= 0 && index < getContainerSize() ? provider.getStackInSlot(index).copy() : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int index, int amount) { return ItemStack.EMPTY; }

    @Override
    public ItemStack removeItemNoUpdate(int index) { return ItemStack.EMPTY; }

    @Override
    public void setItem(int index, ItemStack stack) {}

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) { return false; }

    @Override
    public void setChanged() {}

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() {}
}
