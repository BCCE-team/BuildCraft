package buildcraft.lib.platform.registry;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

/** Loader-specific menu factory binding; no screen or renderer registration occurs here. */
public final class PlatformMenus {
    private PlatformMenus() {}
    public static <T extends AbstractContainerMenu> MenuType<T> create(BCMenuFactory<T> factory) {
        return IMenuTypeExtension.create(factory::create);
    }
}
