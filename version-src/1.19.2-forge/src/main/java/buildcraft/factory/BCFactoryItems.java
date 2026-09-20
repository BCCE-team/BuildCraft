package buildcraft.factory;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.core.BCCore;
import buildcraft.factory.item.ItemWaterGel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public class BCFactoryItems {

    public static final BCDeferredRegister<Item> ITEMS = BCDeferredRegister.create("minecraft:item", BCFactory.MODID);

    public static final BCRegistryEntry<BlockItem> PUMP_BLOCK_ITEM = ITEMS.register("pump", () -> new BlockItem(BCFactoryBlocks.PUMP_BLOCK.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> TANK_BLOCK_ITEM = ITEMS.register("tank", () -> new BlockItem(BCFactoryBlocks.TANK_BLOCK.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> CHUTE_BLOCK_ITEM = ITEMS.register("chute", () -> new BlockItem(BCFactoryBlocks.CHUTE_BLOCK.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> FLOOD_GATE_BLOCK_ITEM = ITEMS.register("flood_gate", () -> new BlockItem(BCFactoryBlocks.FLOOD_GATE_BLOCK.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> MINING_WELL_BLOCK_ITEM = ITEMS.register("mining_well", () -> new BlockItem(BCFactoryBlocks.MINING_WELL_BLOCK.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> DISTILLER_BLOCK_ITEM = ITEMS.register("distiller", () -> new BlockItem(BCFactoryBlocks.DISTILLER_BLOCK.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> HEAT_EXCHANGE_BLOCK_ITEM = ITEMS.register("heat_exchange", () -> new BlockItem(BCFactoryBlocks.HEATEXCHANGE_BLOCK.get(),new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));
    public static final BCRegistryEntry<BlockItem> AUTO_BENCH_ITEM = ITEMS.register("autoworkbench_item", () -> new BlockItem(BCFactoryBlocks.AUTO_BENCH_BLOCK.get(), new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));

    public static final BCRegistryEntry<ItemWaterGel> WATER_GEL_SPAWN = ITEMS.register("water_gel", ItemWaterGel::new);
    public static final BCRegistryEntry<Item> GEL = ITEMS.register("gel", () -> new Item(new Item.Properties().tab(BCCore.BUILDCRAFT_TAB)));


    static void registry(BCRegistryBinder bus) {
        ITEMS.register(bus);
    }
}
