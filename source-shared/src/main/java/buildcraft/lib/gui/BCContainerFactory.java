package buildcraft.lib.gui;

import buildcraft.lib.platform.registry.BCMenuFactory;
import buildcraft.lib.platform.registry.PlatformMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;

public class BCContainerFactory<T extends MenuBC_Neptune> implements BCMenuFactory<T> {
    final BCMenuSupplier<T> constructor;

    public BCContainerFactory(BCMenuSupplier<T> constructor) {
        this.constructor = constructor;
    }

    @Override
    public T create(int windowId, Inventory inv, FriendlyByteBuf data) {
        return constructor.create(windowId, inv, data);
    }

    public interface BCMenuSupplier<T extends MenuBC_Neptune> {
        T create(int windowId, Inventory inv, FriendlyByteBuf data);
    }

    public static <T extends MenuBC_Neptune> MenuType<T> create(BCMenuSupplier<T> constructor) {
        return PlatformMenus.create(new BCContainerFactory<>(constructor));
    }
}
