package buildcraft.robotics;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.core.BCCore;
import buildcraft.robotics.item.ItemRedstoneBoard;
import buildcraft.robotics.item.ItemRobot;
import buildcraft.robotics.item.ItemRobotStation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public class BCRoboticsItems {
    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCRobotics.MODID);

    public static final BCRegistryEntry<ItemRobot> ROBOT = ITEMS.register("robot",
            () -> new ItemRobot(new Item.Properties().tab(BCRobotics.TAB_ROBOTICS).stacksTo(1)));

    public static final BCRegistryEntry<ItemRobotStation> ROBOT_STATION = ITEMS.register("robot_station",
            () -> new ItemRobotStation(new Item.Properties().tab(BCRobotics.TAB_ROBOTICS)));

    public static final BCRegistryEntry<ItemRedstoneBoard> REDSTONE_BOARD = ITEMS.register("redstone_board",
            () -> new ItemRedstoneBoard(new Item.Properties().tab(BCRobotics.TAB_ROBOTICS).stacksTo(16)));

    public static final BCRegistryEntry<BlockItem> ZONE_PLANNER = ITEMS.register("zone_planner",
            () -> new BlockItem(BCRoboticsBlocks.ZONE_PLANNER.get(), new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));

    public static final BCRegistryEntry<BlockItem> REQUESTER = ITEMS.register("requester",
            () -> new BlockItem(BCRoboticsBlocks.REQUESTER.get(), new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));

    public static void registry(BCRegistryBinder bus) {
        ITEMS.register(bus);
    }
}
