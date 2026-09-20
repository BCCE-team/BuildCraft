package buildcraft.factory;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.factory.block.BlockAutoWorkbenchItems;
import buildcraft.factory.block.BlockChute;
import buildcraft.factory.block.BlockDistiller;
import buildcraft.factory.block.BlockFloodGate;
import buildcraft.factory.block.BlockHeatExchange;
import buildcraft.factory.block.BlockMiningWell;
import buildcraft.factory.block.BlockPump;
import buildcraft.factory.block.BlockTank;
import buildcraft.factory.block.BlockTube;
import buildcraft.factory.block.BlockWaterGel;
import buildcraft.factory.tile.TileAutoWorkbenchItems;
import buildcraft.factory.tile.TileChute;
import buildcraft.factory.tile.TileDistiller_BC8;
import buildcraft.factory.tile.TileFloodGate;
import buildcraft.factory.tile.TileHeatExchange;
import buildcraft.factory.tile.TileMiningWell;
import buildcraft.factory.tile.TilePump;
import buildcraft.factory.tile.TileTank;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class BCFactoryBlocks {

    public static final BCDeferredRegister<BlockEntityType<?>> BLOCK_ENTITYS = BCDeferredRegister.create("minecraft:block_entity_type",BCFactory.MODID);
    public static final BCDeferredRegister<Block> BLOCKS = BCDeferredRegister.create("minecraft:block", BCFactory.MODID);
    public static final BCRegistryEntry<Block> PUMP_BLOCK = BLOCKS.register("pump", BlockPump::new);
    public static final BCRegistryEntry<Block> TANK_BLOCK = BLOCKS.register("tank", BlockTank::new);
    public static final BCRegistryEntry<Block> CHUTE_BLOCK = BLOCKS.register("chute", BlockChute::new);
    public static final BCRegistryEntry<Block> FLOOD_GATE_BLOCK = BLOCKS.register("flood_gate",BlockFloodGate::new);
    public static final BCRegistryEntry<Block> TUBE_BLOCK = BLOCKS.register("tube", BlockTube::new);
    public static final BCRegistryEntry<Block> MINING_WELL_BLOCK = BLOCKS.register("mining_well",BlockMiningWell::new);
    public static final BCRegistryEntry<Block> DISTILLER_BLOCK = BLOCKS.register("distiller", BlockDistiller::new);
    public static final BCRegistryEntry<Block> HEATEXCHANGE_BLOCK = BLOCKS.register("heat_exchange", BlockHeatExchange::new);
    public static final BCRegistryEntry<Block> WATER_GEL = BLOCKS.register("water_gel", BlockWaterGel::new);
    public static final BCRegistryEntry<Block> AUTO_BENCH_BLOCK = BLOCKS.register("autoworkbench_item", BlockAutoWorkbenchItems::new);


    public static final BCRegistryEntry<BlockEntityType<TileTank>> ENTITYBLOCKTANK = BLOCK_ENTITYS.register("entity_tank",
            () -> BlockEntityType.Builder.of(TileTank::new,TANK_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TilePump>> ENTITYBLOCKPUMP = BLOCK_ENTITYS.register("entity_pump",
            () -> BlockEntityType.Builder.of(TilePump::new,PUMP_BLOCK.get()).build(null));
//    public static final BCRegistryEntry<BlockEntityType<EntityBlockTube>> ENTITYBLOCKTUBE = BLOCK_ENTITYS.register("entity_tube",
//    		() -> BlockEntityType.Builder.of(EntityBlockTube::new,TUBE_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileFloodGate>> ENTITYBLOCKFLOODGATE = BLOCK_ENTITYS.register("entity_flood_gate",
            () -> BlockEntityType.Builder.of(TileFloodGate::new,FLOOD_GATE_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileMiningWell>> ENTITYBLOCKMININGWELL = BLOCK_ENTITYS.register("entity_mining_well",
            () -> BlockEntityType.Builder.of(TileMiningWell::new,MINING_WELL_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileDistiller_BC8>> ENTITYBLOCKDISTILLER = BLOCK_ENTITYS.register("entity_distiller",
            () -> BlockEntityType.Builder.of(TileDistiller_BC8::new, DISTILLER_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileHeatExchange>> ENTITYBLOCKHEATEXCHANGE = BLOCK_ENTITYS.register("entity_heat_exchange",
            () -> BlockEntityType.Builder.of(TileHeatExchange::new, HEATEXCHANGE_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileAutoWorkbenchItems>> ENTITYBLOCKAUTOBENCH = BLOCK_ENTITYS.register("entity_autoworkbench_item",
            () -> BlockEntityType.Builder.of(TileAutoWorkbenchItems::new, AUTO_BENCH_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileChute>> ENTITYBLOCKCHUTE = BLOCK_ENTITYS.register("entity_chute",
            () -> BlockEntityType.Builder.of(TileChute::new, CHUTE_BLOCK.get()).build(null));




    static void registry(BCRegistryBinder bus){
        BLOCKS.register(bus);
        BLOCK_ENTITYS.register(bus);
    }
}
