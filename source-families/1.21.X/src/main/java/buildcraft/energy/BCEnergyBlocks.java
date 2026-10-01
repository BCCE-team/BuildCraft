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

public class BCEnergyBlocks {
    public static final BCDeferredRegister<Block> BLOCKS = BCDeferredRegister.create("minecraft:block", BCEnergy.MODID);
    public static final BCDeferredRegister<BlockEntityType<?>> BLOCK_ENTITYS =
        BCDeferredRegister.create("minecraft:block_entity_type", BCEnergy.MODID);

    public static final BCRegistryEntry<MultiBlockItem<EnumEngineType>> ENGINE_STONE_ITEM = BCEnergy.ITEMS.register(
        "engine_stone",
        () -> new MultiBlockItem<>(
            BCCoreBlocks.ENGINE_BC8.get(), new Item.Properties(), EnumEngineType.STONE, BCCoreItems.ENGINE_ITEM_MAP
        )
    );
    public static final BCRegistryEntry<MultiBlockItem<EnumEngineType>> ENGINE_IRON_ITEM = BCEnergy.ITEMS.register(
        "engine_iron",
        () -> new MultiBlockItem<>(
            BCCoreBlocks.ENGINE_BC8.get(), new Item.Properties(), EnumEngineType.IRON, BCCoreItems.ENGINE_ITEM_MAP
        )
    );
    public static final BCRegistryEntry<MultiBlockItem<EnumEngineType>> ENGINE_FE_ITEM = BCEnergy.ITEMS.register(
        "engine_fe",
        () -> new MultiBlockItem<>(
            BCCoreBlocks.ENGINE_BC8.get(), new Item.Properties(), EnumEngineType.FE, BCCoreItems.ENGINE_ITEM_MAP
        )
    );
    public static final BCRegistryEntry<BlockDynamoMJ> DYNAMO_MJ = BLOCKS.register(
        "mj_dynamo", () -> new BlockDynamoMJ(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F).explosionResistance(10.0F).noOcclusion())
    );
    public static final BCRegistryEntry<BlockItem> DYNAMO_MJ_ITEM = BCEnergy.ITEMS.register(
        "mj_dynamo", () -> new BlockItem(DYNAMO_MJ.get(), new Item.Properties())
    );

    public static final BCRegistryEntry<BlockEntityType<TileEngineStone_BC8>> ENGINE_STONE_TILE_BC8 =
        BLOCK_ENTITYS.register(
            "entity_stone_engine",
            () -> BlockEntityType.Builder.of(TileEngineStone_BC8::new, BCCoreBlocks.ENGINE_BC8.get()).build(null)
        );
    public static final BCRegistryEntry<BlockEntityType<TileEngineIron_BC8>> ENGINE_IRON_TILE_BC8 =
        BLOCK_ENTITYS.register(
            "entity_iron_engine",
            () -> BlockEntityType.Builder.of(TileEngineIron_BC8::new, BCCoreBlocks.ENGINE_BC8.get()).build(null)
        );
    public static final BCRegistryEntry<BlockEntityType<TileEngineFE>> ENGINE_FE_TILE_BC8 =
        BLOCK_ENTITYS.register(
            "entity_fe_engine", () -> BlockEntityType.Builder.of(TileEngineFE::new, BCCoreBlocks.ENGINE_BC8.get()).build(null)
        );
    public static final BCRegistryEntry<BlockEntityType<TileDynamoMJ>> DYNAMO_MJ_TILE =
        BLOCK_ENTITYS.register(
            "entity_mj_dynamo", () -> BlockEntityType.Builder.of(TileDynamoMJ::new, DYNAMO_MJ.get()).build(null)
        );
    public static final BCRegistryEntry<BlockEntityType<TileSpringOil>> TILE_SPRING =
        BLOCK_ENTITYS.register(
            "entity_spring",
            () -> BlockEntityType.Builder.of(TileSpringOil::new, BCCoreBlocks.SPRING.get()).build(null)
        );

    static void init(BCRegistryBinder bus) {
        BLOCK_ENTITYS.register(bus);
        BLOCKS.register(bus);
    }

    public static List<ItemStack> getCreativeTabItems() {
        return List.of(ENGINE_STONE_ITEM.get().getDefaultInstance(), ENGINE_IRON_ITEM.get().getDefaultInstance(), ENGINE_FE_ITEM.get().getDefaultInstance(), DYNAMO_MJ_ITEM.get().getDefaultInstance());
    }
}
