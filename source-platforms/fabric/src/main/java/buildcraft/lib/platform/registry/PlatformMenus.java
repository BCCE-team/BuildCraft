package buildcraft.lib.platform.registry;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/** Fabric menu type/opening binding. Client screen registration remains in the later client stage. */
public final class PlatformMenus {
    private PlatformMenus() { }

    public static <T extends AbstractContainerMenu> MenuType<T> create(BCMenuFactory<T> factory) {
        return new ExtendedScreenHandlerType<>(factory::create);
    }

    public static void open(ServerPlayer player, MenuProvider provider, BlockPos pos) {
        // ExtendedScreenHandlerType requires the same opening payload that Forge/NeoForge send.
        // Keep that payload loader-owned while the common menu factory stays FriendlyByteBuf-based.
        player.openMenu(new ExtendedScreenHandlerFactory() {
            @Override
            public void writeScreenOpeningData(ServerPlayer openingPlayer, FriendlyByteBuf buffer) {
                buffer.writeBlockPos(pos);
            }

            @Override
            public Component getDisplayName() {
                return provider.getDisplayName();
            }

            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory inventory, Player openingPlayer) {
                return provider.createMenu(syncId, inventory, openingPlayer);
            }
        });
    }
}
