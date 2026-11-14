package buildcraft.energy;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import java.util.List;

import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.core.BCCoreBlocks;
import buildcraft.core.BCCoreItems;
import buildcraft.energy.block.BlockDynamoMJ;
import buildcraft.energy.tile.TileDynamoMJ;
import buildcraft.energy.tile.TileEngineFE;
import buildcraft.energy.tile.TileEngineIron_BC8;
import buildcraft.energy.tile.TileEngineStone_BC8;
import buildcraft.energy.tile.TileSpringOil;
import buildcraft.lib.item.MultiBlockItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import buildcraft.lib.compat.BlockEntityTypeCompat;
import buildcraft.lib.compat.RegistryCompat;

public class BCEnergyBlocks {
    public static final BCDeferredRegister<Block> BLOCKS = BCDeferredRegister.create("minecraft:block", BCEnergy.MODID);
    public static final BCDeferredRegister<BlockEntityType<?>> BLOCK_ENTITYS =
        BCDeferredRegister.create("minecraft:block_entity_type", BCEnergy.MODID);

    public static final BCRegistryEntry<MultiBlockItem<EnumEngineType>> ENGINE_STONE_ITEM = RegistryCompat.registerItem(BCEnergy.ITEMS, "engine_stone",
        () -> new MultiBlockItem<>(
            BCCoreBlocks.ENGINE_BC8.get(), RegistryCompat.itemProperties(new Item.Properties()), EnumEngineType.STONE, BCCoreItems.ENGINE_ITEM_MAP
        )
    );
    public static final BCRegistryEntry<MultiBlockItem<EnumEngineType>> ENGINE_IRON_ITEM = RegistryCompat.registerItem(BCEnergy.ITEMS, "engine_iron",
        () -> new MultiBlockItem<>(
            BCCoreBlocks.ENGINE_BC8.get(), RegistryCompat.itemProperties(new Item.Properties()), EnumEngineType.IRON, BCCoreItems.ENGINE_ITEM_MAP
        )
    );
    public static final BCRegistryEntry<MultiBlockItem<EnumEngineType>> ENGINE_FE_ITEM = RegistryCompat.registerItem(BCEnergy.ITEMS, "engine_fe",
        () -> new MultiBlockItem<>(
            BCCoreBlocks.ENGINE_BC8.get(), RegistryCompat.itemProperties(new Item.Properties()), EnumEngineType.FE, BCCoreItems.ENGINE_ITEM_MAP
        )
    );
    public static final BCRegistryEntry<BlockDynamoMJ> DYNAMO_MJ = RegistryCompat.registerBlock(BLOCKS, "mj_dynamo", () -> new BlockDynamoMJ(RegistryCompat.blockProperties(BlockBehaviour.Properties.of()).mapColor(MapColor.METAL).strength(5.0F).explosionResistance(10.0F).noOcclusion())
    );
    public static final BCRegistryEntry<BlockItem> DYNAMO_MJ_ITEM = RegistryCompat.registerItem(BCEnergy.ITEMS, "mj_dynamo", () -> new BlockItem(DYNAMO_MJ.get(), RegistryCompat.itemProperties(new Item.Properties()))
    );

    public static final BCRegistryEntry<BlockEntityType<TileEngineStone_BC8>> ENGINE_STONE_TILE_BC8 =
        BLOCK_ENTITYS.register(
            "entity_stone_engine",
            () -> BlockEntityTypeCompat.create(TileEngineStone_BC8::new, BCCoreBlocks.ENGINE_BC8.get())
        );
    public static final BCRegistryEntry<BlockEntityType<TileEngineIron_BC8>> ENGINE_IRON_TILE_BC8 =
        BLOCK_ENTITYS.register(
            "entity_iron_engine",
            () -> BlockEntityTypeCompat.create(TileEngineIron_BC8::new, BCCoreBlocks.ENGINE_BC8.get())
        );
    public static final BCRegistryEntry<BlockEntityType<TileEngineFE>> ENGINE_FE_TILE_BC8 =
        BLOCK_ENTITYS.register(
            "entity_fe_engine", () -> BlockEntityTypeCompat.create(TileEngineFE::new, BCCoreBlocks.ENGINE_BC8.get())
        );
    public static final BCRegistryEntry<BlockEntityType<TileDynamoMJ>> DYNAMO_MJ_TILE =
        BLOCK_ENTITYS.register(
            "entity_mj_dynamo", () -> BlockEntityTypeCompat.create(TileDynamoMJ::new, DYNAMO_MJ.get())
        );
    public static final BCRegistryEntry<BlockEntityType<TileSpringOil>> TILE_SPRING =
        BLOCK_ENTITYS.register(
            "entity_spring",
            () -> BlockEntityTypeCompat.create(TileSpringOil::new, BCCoreBlocks.SPRING.get())
        );

    static void init(BCRegistryBinder bus) {
        // The MJ dynamo block entity resolves its owner block eagerly. Register
        // blocks before block-entity types on Fabric, unlike event-driven NeoForge.
        BLOCKS.register(bus);
        BLOCK_ENTITYS.register(bus);
    }

    public static List<ItemStack> getCreativeTabItems() {
        return List.of(ENGINE_STONE_ITEM.get().getDefaultInstance(), ENGINE_IRON_ITEM.get().getDefaultInstance(), ENGINE_FE_ITEM.get().getDefaultInstance(), DYNAMO_MJ_ITEM.get().getDefaultInstance());
    }
}
