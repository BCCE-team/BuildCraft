package buildcraft.core;

import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.lib.internal.enums.EnumEngineType;
import buildcraft.core.block.BlockEngine_BC8;
import buildcraft.core.block.BlockMarkerPath;
import buildcraft.core.block.BlockMarkerVolume;
import buildcraft.core.block.BlockSpring;
import buildcraft.core.blockEntity.TileEngineCreative;
import buildcraft.core.blockEntity.TileEngineRedstone_BC8;
import buildcraft.core.blockEntity.TileMarkerPath;
import buildcraft.core.blockEntity.TileMarkerVolume;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import buildcraft.lib.compat.BlockEntityTypeCompat;
import buildcraft.lib.compat.RegistryCompat;

public class BCCoreBlocks {
    public static final BCDeferredRegister<BlockEntityType<?>> BLOCK_ENTITYS = BCDeferredRegister.create("minecraft:block_entity_type", BCCore.MODID);

    public static final BCDeferredRegister<Block> BLOCKS = BCDeferredRegister.create("minecraft:block", BCCore.MODID);
//    public static final BCRegistryEntry<Block> ENGINE_RESTONE_BLOCK = RegistryCompat.registerBlock(BLOCKS, "engine_redstone", () -> new BlockEngine(TileEngineRedstone::new,"redstone"));
//    public static final BCRegistryEntry<Block> ENGINE_CREATIVE_BLOCK = RegistryCompat.registerBlock(BLOCKS, "engine_creative", () -> new BlockEngine(TileEngineCreative::new,"creative"));



    public static final BCRegistryEntry<Block> ENGINE_BC8 = RegistryCompat.registerBlock(BLOCKS, "engine", () -> new BlockEngine_BC8(RegistryCompat.blockProperties(BlockBehaviour.Properties.of()).mapColor(MapColor.METAL)
            .strength(25.0f).explosionResistance(10.0f).dynamicShape().noOcclusion().requiresCorrectToolForDrops())
            .registerEngine(EnumEngineType.WOOD, TileEngineRedstone_BC8::new)
            .registerEngine(EnumEngineType.CREATIVE, TileEngineCreative::new));

    public static final BCRegistryEntry<BlockSpring> SPRING = RegistryCompat.registerBlock(BLOCKS, "spring", BlockSpring::new);
    public static final BCRegistryEntry<BlockMarkerPath> MARKER_PATH = RegistryCompat.registerBlock(BLOCKS, "marker_path", BlockMarkerPath::new);
    public static final BCRegistryEntry<BlockMarkerVolume> MARKER_VOLUME = RegistryCompat.registerBlock(BLOCKS, "marker_volume", BlockMarkerVolume::new);
//    public static BlockMarkerPath markerPath;


/*    public static final BCRegistryEntry<BlockEntityType<TileEngineRedstone>> ENGINE_REDSTONE_TILE = BLOCK_ENTITYS.register("entity_engine_redstone",
            () -> BlockEntityTypeCompat.create(TileEngineRedstone::new,ENGINE_RESTONE_BLOCK.get()));
    public static final BCRegistryEntry<BlockEntityType<TileEngineCreative>> ENGINE_CREATIVE_TILE = BLOCK_ENTITYS.register("entity_engine_creative",
            () -> BlockEntityTypeCompat.create(TileEngineCreative::new,ENGINE_CREATIVE_BLOCK.get()));*/

    public static final BCRegistryEntry<BlockEntityType<TileEngineRedstone_BC8>> ENGINE_REDSTONE_TILE_BC8 = BLOCK_ENTITYS.register("entity_engine_redstone",
            () -> BlockEntityTypeCompat.create(TileEngineRedstone_BC8::new,ENGINE_BC8.get()));

    public static final BCRegistryEntry<BlockEntityType<TileEngineCreative>> ENGINE_CREATIVE_TILE_BC8 = BLOCK_ENTITYS.register("entity_engine_creative",
            () -> BlockEntityTypeCompat.create(TileEngineCreative::new,ENGINE_BC8.get()));

    public static final BCRegistryEntry<BlockEntityType<TileMarkerPath>> MARKER_PATH_TILE_BC8 = BLOCK_ENTITYS.register("entity_marker_path",
            () -> BlockEntityTypeCompat.create(TileMarkerPath::new,MARKER_PATH.get()));

    public static final BCRegistryEntry<BlockEntityType<TileMarkerVolume>> MARKER_VOLUME_TILE_BC8 = BLOCK_ENTITYS.register("entity_marker_volume",
            () -> BlockEntityTypeCompat.create(TileMarkerVolume::new,MARKER_VOLUME.get()));

    static void registry(BCRegistryBinder m) {
        BLOCKS.register(m);
        BLOCK_ENTITYS.register(m);
    }

}
