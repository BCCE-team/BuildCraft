//? source if >=26.3
package buildcraft.builders.compat;

import buildcraft.lib.tile.item.ItemHandlerSimple;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** Vanilla slot inventory backed by the three live Replacer handlers. */
public final class BuildersItemContainer263 implements Container {
    private final ItemHandlerSimple[] handlers;
    private final ItemStack[] visible;
    private final ItemStack[] lastKnown;

    public BuildersItemContainer263(ItemHandlerSimple... handlers) {
        this.handlers = handlers.clone();
        this.visible = new ItemStack[handlers.length];
        this.lastKnown = new ItemStack[handlers.length];
        for (int index = 0; index < handlers.length; index++) {
            visible[index] = handlers[index].getStackInSlot(0).copy();
            lastKnown[index] = visible[index].copy();
        }
    }

    private boolean valid(int index) { return index >= 0 && index < handlers.length; }

    @Override
    public int getContainerSize() { return handlers.length; }

    @Override
    public boolean isEmpty() {
        for (int index = 0; index < handlers.length; index++) if (!getItem(index).isEmpty()) return false;
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        if (!valid(index)) return ItemStack.EMPTY;
        if (ItemStack.matches(visible[index], lastKnown[index])) {
            ItemStack actual = handlers[index].getStackInSlot(0);
            if (!ItemStack.matches(actual, lastKnown[index])) {
                visible[index] = actual.copy();
                lastKnown[index] = actual.copy();
            }
        }
        return visible[index];
    }

    @Override
    public ItemStack removeItem(int index, int amount) {
        if (!valid(index) || amount <= 0) return ItemStack.EMPTY;
        ItemStack result = getItem(index).split(amount);
        setChanged();
        return result;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        if (!valid(index)) return ItemStack.EMPTY;
        ItemStack old = getItem(index).copy();
        setItem(index, ItemStack.EMPTY);
        return old;
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (!valid(index)) return;
        if (!stack.isEmpty() && !handlers[index].canSet(0, stack)) return;
        visible[index] = stack.copy();
        visible[index].limitSize(1);
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return valid(index) && handlers[index].canSet(0, stack);
    }

    @Override
    public int getMaxStackSize() { return 1; }

    @Override
    public void setChanged() {
        for (int index = 0; index < handlers.length; index++) {
            ItemStack candidate = visible[index];
            if (!ItemStack.matches(candidate, lastKnown[index])) {
                if (handlers[index].canSet(0, candidate)) {
                    handlers[index].setStackInSlot(0, candidate.copy());
                    lastKnown[index] = candidate.copy();
                } else {
                    visible[index] = handlers[index].getStackInSlot(0).copy();
                    lastKnown[index] = visible[index].copy();
                }
            }
        }
    }

    @Override
    public boolean stillValid(Player player) { return true; }

    @Override
    public void clearContent() {
        for (int index = 0; index < handlers.length; index++) setItem(index, ItemStack.EMPTY);
    }
}
