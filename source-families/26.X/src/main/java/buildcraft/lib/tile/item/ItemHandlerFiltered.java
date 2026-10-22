package buildcraft.lib.tile.item;

import buildcraft.lib.internal.inventory.IItemHandlerFiltered;
import buildcraft.lib.misc.StackUtil;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** A type of {@link ItemHandlerSimple} that gets it's {@link IItemHandlerFiltered#getFilter(int)} from a given
 * {@link IItemHandler} instance. This currently instantiates to having the same {@link IItemHandler#getSlots() slot
 * count} as the filter. */
public class ItemHandlerFiltered extends ItemHandlerSimple implements IItemHandlerFiltered {
    private final IItemHandler filter;
    private final boolean emptyIsAnything;

    public ItemHandlerFiltered(IItemHandler filter, boolean emptyIsAnything) {
        super(filter.getSlots());
        this.emptyIsAnything = emptyIsAnything;
        this.filter = filter;
        setChecker((slot, stack) -> {
            ItemStack inSlot = filter.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                return emptyIsAnything;
            } else {
                return StackUtil.canMerge(stack, inSlot);
            }
        });
    }

    public int getSlotLimit(int slot) {
        if (emptyIsAnything || !getFilter(slot).isEmpty()) {
            return super.getSlotLimit(slot);
        } else {
            return 0;
        }
    }

    public ItemStack getFilter(int slot) {
        ItemStack current = getStackInSlot(slot);
        if (!current.isEmpty()) {
            return current;
        }
        return filter.getStackInSlot(slot);
    }
}
