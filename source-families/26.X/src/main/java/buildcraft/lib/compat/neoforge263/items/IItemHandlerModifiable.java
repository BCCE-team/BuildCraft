//? source if >=26.3
package buildcraft.lib.compat.neoforge263.items;

import net.minecraft.world.item.ItemStack;

public interface IItemHandlerModifiable extends IItemHandler {
    void setStackInSlot(int slot, ItemStack stack);
}
