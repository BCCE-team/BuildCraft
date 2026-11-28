/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.fabric;

import java.util.function.Predicate;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.silicon.BCSiliconBlocks;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.rendering.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.model.SpriteFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric 1.21.11 does not interpret NeoForge's JSON render_type declaration.
 * Supply the equivalent terrain layers without changing the model geometry.
 */
final class FabricBlockTransparency {
    private static final Identifier HEAT_GLASS =
        Identifier.fromNamespaceAndPath("buildcraftfactory", "block/heat_exchange/glass");
    private static final Identifier TABLE_GLASS =
        Identifier.fromNamespaceAndPath("minecraft", "block/white_stained_glass");

    private FabricBlockTransparency() {}

    static void register() {
        // Binary alpha in the frame sprite: discard empty pixels, keep the lattice opaque.
        BlockRenderLayerMap.putBlock(BCBuildersBlocks.FRAME.get(), ChunkSectionLayer.CUTOUT);
        BlockRenderLayerMap.putBlock(BCFactoryBlocks.TANK_BLOCK.get(), ChunkSectionLayer.CUTOUT);

        // The fallback layer protects the glass in contexts which only consult the
        // block-wide render layer, instead of the per-quad terrain layer.
        BlockRenderLayerMap.putBlocks(ChunkSectionLayer.TRANSLUCENT,
            BCFactoryBlocks.HEATEXCHANGE_BLOCK.get(), BCSiliconBlocks.PROGRAMMING_TABLE_BLOCK.get());

        ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake().register((model, info) -> {
            if (info.state().is(BCFactoryBlocks.HEATEXCHANGE_BLOCK.get())) {
                return new LayeredMachineModel(model, HEAT_GLASS, ChunkSectionLayer.SOLID);
            }
            if (info.state().is(BCSiliconBlocks.PROGRAMMING_TABLE_BLOCK.get())) {
                // The programming table's top, sides and center have binary alpha too.
                return new LayeredMachineModel(model, TABLE_GLASS, ChunkSectionLayer.CUTOUT);
            }
            return model;
        }));
    }

    /** Keep multipart rotations, face culling, AO and other baked-model properties. */
    private static final class LayeredMachineModel extends WrapperBlockStateModel {
        private final Identifier glassSprite;
        private final ChunkSectionLayer bodyLayer;

        LayeredMachineModel(BlockStateModel wrapped, Identifier glassSprite, ChunkSectionLayer bodyLayer) {
            super(wrapped);
            this.glassSprite = glassSprite;
            this.bodyLayer = bodyLayer;
        }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
            BlockState state, RandomSource random, Predicate<Direction> cullTest) {
            // Look up the current atlas rather than retaining stale UVs across F3+T.
            var atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            SpriteFinder sprites = SpriteFinder.get(atlas);
            emitter.pushTransform(quad -> {
                Identifier sprite = sprites.find(quad).contents().name();
                if (glassSprite.equals(sprite)) {
                    quad.renderLayer(ChunkSectionLayer.TRANSLUCENT);
                } else if (quad.renderLayer() == null) {
                    quad.renderLayer(bodyLayer);
                }
                return true;
            });
            try {
                super.emitQuads(emitter, level, pos, state, random, cullTest);
            } finally {
                emitter.popTransform();
            }
        }

        @Override
        public Object createGeometryKey(BlockAndTintGetter level, BlockPos pos,
            BlockState state, RandomSource random) {
            Object original = super.createGeometryKey(level, pos, state, random);
            return original == null ? null : new LayeredGeometryKey(original, glassSprite, bodyLayer);
        }
    }

    // Do not share a renderer cache entry with the unmodified model or another layer policy.
    private record LayeredGeometryKey(Object original, Identifier glassSprite, ChunkSectionLayer bodyLayer) {}
}
