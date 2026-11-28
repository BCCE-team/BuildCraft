//? source if >=26.3 || (fabric && >=26.2)
package buildcraft.lib.compat.neoforge263.items;

import net.minecraft.world.item.ItemStack;

public interface IItemHandlerModifiable extends IItemHandler {
    void setStackInSlot(int slot, ItemStack stack);
}
