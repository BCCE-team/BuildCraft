package buildcraft.factory;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.factory.client.gui.MenuHeatExchange;
import buildcraft.factory.container.ContainerAutoCraftItems;
import buildcraft.factory.container.ContainerChute;
import buildcraft.factory.container.ContainerTank;
import buildcraft.lib.gui.BCContainerFactory;
import net.minecraft.world.inventory.MenuType;

public class BCFactoryGuis {
    public static final BCDeferredRegister<MenuType<?>> MENUS =
        BCDeferredRegister.create("minecraft:menu", BCFactory.MODID);

    public static final BCRegistryEntry<MenuType<ContainerAutoCraftItems>> MENU_AUTOWORK_BENCH_ITEM =
        MENUS.register("menu.autoworkbench_item", () -> BCContainerFactory.create(ContainerAutoCraftItems::create));
    public static final BCRegistryEntry<MenuType<MenuHeatExchange>> MENU_HEAT_EXCHANGE =
        MENUS.register("menu.heat_exchange", () -> BCContainerFactory.create(MenuHeatExchange::new));
    public static final BCRegistryEntry<MenuType<ContainerChute>> MENU_CHUTE =
        MENUS.register("menu.chute", () -> BCContainerFactory.create(ContainerChute::new));
    public static final BCRegistryEntry<MenuType<ContainerTank>> MENU_TANK =
        MENUS.register("menu.tank", () -> BCContainerFactory.create(ContainerTank::new));

    static void registry(BCRegistryBinder bus) {
        MENUS.register(bus);
    }
}
