package buildcraft.lib.platform.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

/** Loader-specific menu factory/opening binding; gameplay never calls loader networking helpers directly. */
public final class PlatformMenus {
    private PlatformMenus() {}

    public static <T extends AbstractContainerMenu> MenuType<T> create(BCMenuFactory<T> factory) {
        return IMenuTypeExtension.create(factory::create);
    }

    public static void open(ServerPlayer player, MenuProvider provider, BlockPos pos) {
        player.openMenu(provider, buffer -> buffer.writeBlockPos(pos));
    }

}
