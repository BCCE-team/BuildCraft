package buildcraft.lib.tile.item;

import buildcraft.lib.internal.inventory.IItemHandlerFiltered;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;

/** Combines several {@link IItemHandlerModifiable} into one class. Extends forge's {@link CombinedInvWrapper} in order
 * to do this.
 * <p>
 * Also provides {@link IItemHandlerFiltered#getFilter(int)} if the wrapped handlers support it. */
public class CombinedItemHandlerWrapper extends CombinedInvWrapper implements IItemHandlerFiltered {

    public CombinedItemHandlerWrapper(IItemHandlerModifiable... itemHandler) {
        super(itemHandler);
    }

    public ItemStack getFilter(int slot) {
        int index = getIndexForSlot(slot);
        IItemHandlerModifiable handler = getHandlerFromIndex(index);
        slot = getSlotFromIndex(slot, index);
        if (handler instanceof IItemHandlerFiltered) {
            return ((IItemHandlerFiltered) handler).getFilter(slot);
        }
        return handler.getStackInSlot(slot);
    }
}
