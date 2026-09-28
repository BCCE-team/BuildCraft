package buildcraft.core;

import java.util.Map;

import buildcraft.core.list.ContainerList;
import buildcraft.lib.CreativeTabManager.CreativeTabBC;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;

/** Loader-neutral legacy Core facade used by unified-loader targets. */
public final class BCCore {
    public static final String MODID = LegacyCoreModule.MODID;
    public static final CreativeTabBC BUILDCRAFT_TAB = LegacyCoreModule.BUILDCRAFT_TAB;
    public static final CreativeTabBC tabFluids = LegacyCoreModule.TAB_FLUIDS;
    public static final BCRegistryEntry<CreativeModeTab> MAIN_TAB = LegacyCoreModule.MAIN_TAB;
    public static final BCRegistryEntry<CreativeModeTab> FLUID_TAB = LegacyCoreModule.FLUID_TAB;
    public static final Map<String, Object> ENGINE_MAP = LegacyCoreModule.ENGINE_MAP;
    public static final BCDeferredRegister<MenuType<?>> MENUS = LegacyCoreModule.MENUS;
    public static final BCRegistryEntry<MenuType<ContainerList>> LIST_MENU = LegacyCoreModule.LIST_MENU;

    private BCCore() {
    }
}
