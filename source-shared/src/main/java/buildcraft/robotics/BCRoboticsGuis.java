package buildcraft.robotics;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.gui.BCContainerFactory;
import buildcraft.robotics.container.ContainerRequester;
import buildcraft.robotics.container.ContainerZonePlanner;
import net.minecraft.world.inventory.MenuType;

public final class BCRoboticsGuis {
    public static final BCDeferredRegister<MenuType<?>> MENUS = BCDeferredRegister.create("minecraft:menu", BCRobotics.MODID);

    public static final BCRegistryEntry<MenuType<ContainerZonePlanner>> MENU_ZONE_PLANNER = MENUS.register(
            "menu.zone_planner",
            () -> BCContainerFactory.create(ContainerZonePlanner::new)
    );

    public static final BCRegistryEntry<MenuType<ContainerRequester>> MENU_REQUESTER = MENUS.register(
            "menu.requester",
            () -> BCContainerFactory.create(ContainerRequester::new)
    );

    private BCRoboticsGuis() {
    }


    public static void registry(BCRegistryBinder bus) {
        MENUS.register(bus);
    }
}
