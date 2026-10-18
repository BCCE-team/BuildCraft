package buildcraft.lib.platform.storage;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Container view for a sided native item capability, used by legacy robot station tasks.
 * All removals go through the capability, never by mutating its returned stack. */
public final class ItemStorageContainer implements Container {
    private final ItemStorage handler;
    private final BlockEntity owner;

    public ItemStorageContainer(ItemStorage handler, BlockEntity owner) {
        this.handler = handler;
        this.owner = owner;
    }

    @Override
    public int getContainerSize() {
        return handler.getSlots();
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < handler.getSlots(); i++) {
            if (!handler.getStackInSlot(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int index) {
        if (index < 0 || index >= handler.getSlots()) return ItemStack.EMPTY;
        ItemStack stack = handler.getStackInSlot(index);
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    @Override
    public ItemStack removeItem(int index, int amount) {
        if (index < 0 || index >= handler.getSlots() || amount <= 0) return ItemStack.EMPTY;
        ItemStack taken = handler.extractItem(index, amount, false);
        if (taken != null && !taken.isEmpty()) setChanged();
        return taken == null ? ItemStack.EMPTY : taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int index) {
        if (index < 0 || index >= handler.getSlots()) return ItemStack.EMPTY;
        return removeItem(index, getItem(index).getCount());
    }

    @Override
    public void setItem(int index, ItemStack stack) {
        if (index < 0 || index >= handler.getSlots()) return;
        if (handler instanceof MutableItemStorage mutable) {
            mutable.setStackInSlot(index, stack);
            setChanged();
        }
        // Unmodifiable native handlers support only insert/extract, not arbitrary stack replacement.
    }

    @Override
    public void setChanged() {
        owner.setChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        Level level = owner.getLevel();
        BlockPos pos = owner.getBlockPos();
        return level != null && level.hasChunkAt(pos) && level.getBlockEntity(pos) == owner
                && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = getItem(i);
            if (!stack.isEmpty()) removeItem(i, stack.getCount());
        }
    }
}
