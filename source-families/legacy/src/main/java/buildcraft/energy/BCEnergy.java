package buildcraft.energy;

import buildcraft.lib.platform.registry.BCDeferredRegister;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;

/** Loader-neutral legacy Energy facade used by unified-loader targets. */
public final class BCEnergy {
    public static final String MODID = LegacyEnergyModule.MODID;
    public static final BCDeferredRegister<Item> ITEMS = LegacyEnergyModule.ITEMS;
    public static final BCDeferredRegister<MenuType<?>> MENUS = LegacyEnergyModule.MENUS;

    private BCEnergy() {
    }
}
