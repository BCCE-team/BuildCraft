package buildcraft.lib.platform.registry;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Menu payload factory, independent of the loader's menu-type extension. */
@FunctionalInterface
public interface BCMenuFactory<T extends AbstractContainerMenu> {
    T create(int windowId, Inventory inventory, FriendlyByteBuf extraData);
}
