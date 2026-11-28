/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.fabric;

import java.util.function.Predicate;

import buildcraft.factory.BCFactoryBlocks;
import buildcraft.core.BCCoreBlocks;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.wrapper.WrapperBlockStateModel;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.ShadeMode;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Apply the tank cutout policy and explicit indicator lighting to the native Fabric terrain mesh. */
final class FabricBlockTransparency {
    private FabricBlockTransparency() {}

    static void register() {
        ModelLoadingPlugin.register(context -> context.modifyBlockModelAfterBake().register((model, info) -> {
            if (info.state().is(BCFactoryBlocks.TANK_BLOCK.get())) return new CutoutTankModel(model);
            if (info.state().is(BCCoreBlocks.MARKER_VOLUME.get()) || info.state().is(BCCoreBlocks.MARKER_PATH.get())) {
                return new LitMarkerModel(model);
            }
            return model;
        }));
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

    /** The last two model pixels are the coloured indicator. Do not light up the metal stem. */
    private static final class LitMarkerModel extends WrapperBlockStateModel {
        LitMarkerModel(BlockStateModel wrapped) { super(wrapped); }

        @Override
        public void emitQuads(QuadEmitter emitter, BlockAndTintGetter level, BlockPos pos,
            BlockState state, RandomSource random, Predicate<Direction> cullTest) {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            emitter.pushTransform(quad -> {
                // Preserve the vanilla lighting model on the metal and the original shade=false policy.
                quad.shadeMode(ShadeMode.VANILLA);
                quad.diffuseShade(false);
                if (isLampFace(quad, facing)) {
                    quad.ambientOcclusion(TriState.FALSE);
                    quad.emissive(true);
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
            return original == null ? null : new MarkerGeometryKey(original, state.getValue(BlockStateProperties.FACING));
        }
    }

    /** Test the head in block-local coordinates after vanilla has rotated the model. */
    static boolean isLampFace(QuadView quad, Direction facing) {
        for (int vertex = 0; vertex < 4; vertex++) {
            float fromBase = switch (facing) {
                case UP -> quad.y(vertex);
                case DOWN -> 1.0F - quad.y(vertex);
                case EAST -> quad.x(vertex);
                case WEST -> 1.0F - quad.x(vertex);
                case SOUTH -> quad.z(vertex);
                case NORTH -> 1.0F - quad.z(vertex);
            };
            if (fromBase < 7.0F / 16.0F - 0.0001F) return false;
        }
        return true;
    }

    private record MarkerGeometryKey(Object original, Direction facing) {}
    private record CutoutGeometryKey(Object original) {}
}
