//? source if >=26.3
package buildcraft.silicon.compat;

import java.util.function.IntFunction;
import buildcraft.lib.gui.ItemProvider;
import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public final class SiliconDisplayContainer263 implements Container {
    private final int size;
    private final IntFunction<ItemStack> contents;
    // Menu synchronization writes display-only copies here. Never mutate the backing provider.
    private final ItemStack[] syncedContents;

    public SiliconDisplayContainer263(ItemProvider display) {
        this(display.getSlots(), display::getStackInSlot);
    }

    public SiliconDisplayContainer263(ItemHandlerSimple display) {
        this(display.getSlots(), display::getStackInSlot);
    }

    private SiliconDisplayContainer263(int size, IntFunction<ItemStack> contents) {
        this.size = size;
        this.contents = contents;
        this.syncedContents = new ItemStack[size];
    }

    public Slot slot(int index, int x, int y) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return new Slot(this, index, x, y) {
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
            @Override public ItemStack remove(int amount) { return ItemStack.EMPTY; }
            @Override public ItemStack safeTake(int min, int max, Player player) { return ItemStack.EMPTY; }
        };
    }

    @Override public int getContainerSize() { return size; }

    @Override public boolean isEmpty() {
        for (int index = 0; index < size; index++) {
            if (!getItem(index).isEmpty()) return false;
        }
        return true;
    }

    @Override public ItemStack getItem(int index) {
        if (index < 0 || index >= size) return ItemStack.EMPTY;
        ItemStack synchronizedItem = syncedContents[index];
        if (synchronizedItem != null) return synchronizedItem.copy();
        ItemStack item = contents.apply(index);
        return item == null ? ItemStack.EMPTY : item.copy();
    }

    @Override public ItemStack removeItem(int index, int amount) { return ItemStack.EMPTY; }
    @Override public ItemStack removeItemNoUpdate(int index) { return ItemStack.EMPTY; }
    @Override public void setItem(int index, ItemStack stack) {
        if (index < 0 || index >= size) return;
        syncedContents[index] = stack == null ? ItemStack.EMPTY : stack.copy();
    }
    @Override public boolean canPlaceItem(int index, ItemStack stack) { return false; }
    @Override public void setChanged() {}
    @Override public boolean stillValid(Player player) { return true; }
    @Override public void clearContent() {
        java.util.Arrays.fill(syncedContents, null);
    }
}
