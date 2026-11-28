package buildcraft.lib.compat.neoforge263.fluids;

import net.minecraft.world.item.ItemStack;

public interface IFluidHandlerItem extends IFluidHandler {
    ItemStack getContainer();
}
