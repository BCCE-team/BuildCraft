package buildcraft.silicon;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.internal.enums.EnumLaserTableType;
import buildcraft.silicon.block.BlockLaser;
import buildcraft.silicon.block.BlockLaserTable;
import buildcraft.silicon.tile.TileAdvancedCraftingTable;
import buildcraft.silicon.tile.TileAssemblyTable;
import buildcraft.silicon.tile.TileChargingTable;
import buildcraft.silicon.tile.TileIntegrationTable;
import buildcraft.silicon.tile.TileLaser;
import buildcraft.silicon.tile.TileProgrammingTable_Neptune;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class BCSiliconBlocks {

    public static final BCDeferredRegister<Block> BLOCKS = BCDeferredRegister.create("minecraft:block", BCSilicon.MODID);
    public static final BCDeferredRegister<BlockEntityType<?>> BLOCK_ENTITYS = BCDeferredRegister.create("minecraft:block_entity_type", BCSilicon.MODID);
    public static final BCRegistryEntry<Block> LASER_BLOCK = BLOCKS.register("laser", BlockLaser::new);
    public static final BCRegistryEntry<Block> ASSEMBLY_TABLE_BLOCK = BLOCKS.register("assembly_table", () -> new BlockLaserTable(EnumLaserTableType.ASSEMBLY_TABLE));
    public static final BCRegistryEntry<Block> CHARGING_TABLE_BLOCK = BLOCKS.register("charging_table", () -> new BlockLaserTable(EnumLaserTableType.CHARGING_TABLE));
    public static final BCRegistryEntry<Block> INTERGRATION_TABLE_BLOCK = BLOCKS.register("integration_table", () -> new BlockLaserTable(EnumLaserTableType.INTEGRATION_TABLE));
    public static final BCRegistryEntry<Block> ADVANCED_CRAFTING_TABLE_BLOCK = BLOCKS.register("advanced_crafting_table", () -> new BlockLaserTable(EnumLaserTableType.ADVANCED_CRAFTING_TABLE));
    public static final BCRegistryEntry<Block> PROGRAMMING_TABLE_BLOCK = BLOCKS.register("programming_table", () -> new BlockLaserTable(EnumLaserTableType.PROGRAMMING_TABLE));


    public static final BCRegistryEntry<BlockEntityType<TileLaser>> LASER_TILE = BLOCK_ENTITYS.register("entity_laser",
            () -> BlockEntityType.Builder.of(TileLaser::new,LASER_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileAssemblyTable>> ASSEMBLY_TABLE_TILE = BLOCK_ENTITYS.register("entity_assembly_table",
            () -> BlockEntityType.Builder.of(TileAssemblyTable::new,ASSEMBLY_TABLE_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileChargingTable>> CHARGING_TABLE_TILE = BLOCK_ENTITYS.register("entity_charging_table",
            () -> BlockEntityType.Builder.of(TileChargingTable::new,CHARGING_TABLE_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileIntegrationTable>> INTERGRATION_TABLE_TILE = BLOCK_ENTITYS.register("entity_integration_table",
            () -> BlockEntityType.Builder.of(TileIntegrationTable::new,INTERGRATION_TABLE_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileAdvancedCraftingTable>> ADVANCED_CRAFTING_TABLE_TILE = BLOCK_ENTITYS.register("entity_advanced_crafting_table",
            () -> BlockEntityType.Builder.of(TileAdvancedCraftingTable::new,ADVANCED_CRAFTING_TABLE_BLOCK.get()).build(null));
    public static final BCRegistryEntry<BlockEntityType<TileProgrammingTable_Neptune>> PROGRAMMING_TABLE_TILE = BLOCK_ENTITYS.register("entity_programming_table",
            () -> BlockEntityType.Builder.of(TileProgrammingTable_Neptune::new,PROGRAMMING_TABLE_BLOCK.get()).build(null));


    public static void registry(BCRegistryBinder b) {
        BLOCKS.register(b);
        BLOCK_ENTITYS.register(b);
    }
}
