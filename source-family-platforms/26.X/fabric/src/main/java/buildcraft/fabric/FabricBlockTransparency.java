/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.fabric;

import java.util.function.Predicate;

import buildcraft.factory.BCFactoryBlocks;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Apply the tank's NeoForge JSON cutout policy to the native Fabric terrain mesh. */
final class FabricBlockTransparency {
    private FabricBlockTransparency() {}

    static void register() {
        ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake().register((model, info) ->
            info.state().is(BCFactoryBlocks.TANK_BLOCK.get()) ? new CutoutTankModel(model) : model));
    }

    private static final class CutoutTankModel extends WrapperBlockStateModel {
        CutoutTankModel(BlockStateModel wrapped) { super(wrapped); }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
            BlockState state, RandomSource random, Predicate<Direction> cullTest) {
            emitter.pushTransform(quad -> {
                quad.chunkLayer(ChunkSectionLayer.CUTOUT);
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
            return original == null ? null : new CutoutGeometryKey(original);
        }
    }

    private record CutoutGeometryKey(Object original) {}
}
