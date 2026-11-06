//? source if >=1.21.10
/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * Based on the BuildCraft Redux blueprint block-state preview renderer (MPL-2.0).
 * Render-only implementation: snapshots and build decisions are unchanged.
 */
package buildcraft.builders.client.render.pip;

import com.mojang.blaze3d.vertex.PoseStack;
import buildcraft.lib.engine.BlockEngineBase_BC8;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.1 {
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import buildcraft.lib.compat.RenderCompat;
//?}
//? if >=26.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;*/
//?}

/** The actual block-state model (rather than the inventory-item model) used for Blueprint cells. */
public final class PreviewBlockModelRenderer {
    private PreviewBlockModelRenderer() {}

    /** Scoped to one preview pass so weighted block variants are decoded once per distinct state. */
    public static final class CellCache {
        //? if >=26.1 {
        private final Map<BlockState, List<BakedQuad>> quads = new HashMap<>();
        //?}
    }

    //? if >=26.2 {
    public static boolean renderBlock(BlockState state, PoseStack pose, SubmitNodeCollector sink,
        CellCache cache, int light, int overlay) {
    //?} else {
    /*public static boolean renderBlock(BlockState state, PoseStack pose, MultiBufferSource.BufferSource sink,
        CellCache cache, int light, int overlay) {*/
    //?}
        // Engine body geometry is implemented by its BER, not the blockstate model.
        if (state.getBlock() instanceof BlockEngineBase_BC8) return false;
        //? if >=26.1 {
        List<BakedQuad> quads = quadsFor(state, cache);
        if (quads.isEmpty()) return false;
        //? if >=26.2 {
        sink.submitCustomGeometry(pose, RenderCompat.cutout(),
            (geometryPose, consumer) -> emit(consumer, geometryPose, quads, state, light, overlay));
        //?} else {
        /*emit(sink.getBuffer(RenderCompat.cutout()), pose.last(), quads, state, light, overlay);*/
        //?}
        return true;
        //?} else {
        /*Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, sink, light, overlay,
            net.minecraft.world.level.EmptyBlockAndTintGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
        return true;*/
        //?}
    }

    //? if >=26.1 {
    private static List<BakedQuad> quadsFor(BlockState state, CellCache cache) {
        return cache.quads.computeIfAbsent(state, blockState -> {
            BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(blockState);
            if (model == null) return List.of();
            // Reset the weighted variant seed for every unique state: no flickering.
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);
            List<BakedQuad> result = new ArrayList<>();
            for (BlockStateModelPart part : parts) {
                for (Direction side : Direction.values()) result.addAll(part.getQuads(side));
                result.addAll(part.getQuads(null));
            }
            return result;
        });
    }
    //?}

    //? if >=26.2 {
    /** One GPU draw layer for all ordinary block models of one PiP pass. */
    public static final class Batch {
        private record Cell(BlockPos position, BlockState state, List<BakedQuad> quads,
                            int light, int overlay) {}
        private final List<Cell> cells = new ArrayList<>();
        private final java.util.Set<BlockPos> occupied = new java.util.HashSet<>();

        public boolean contains(BlockPos at) {
            return occupied.contains(at);
        }

        public int size() { return cells.size(); }

        public boolean add(BlockState state, BlockPos at, CellCache cache, int light, int overlay) {
            if (state == null || state.getBlock() instanceof BlockEngineBase_BC8) return false;
            List<BakedQuad> quads = quadsFor(state, cache);
            if (quads.isEmpty()) return false;
            BlockPos position = at.immutable();
            if (occupied.add(position)) cells.add(new Cell(position, state, quads, light, overlay));
            return true;
        }

        public void submit(PoseStack parent, SubmitNodeCollector collector) {
            if (cells.isEmpty()) return;
            // The batch is immutable after its initial assembly. Capturing the
            // stable list avoids copying thousands of cell records per redraw.
            List<Cell> draw = cells;
            collector.submitCustomGeometry(parent, RenderCompat.cutout(), (pose, consumer) -> {
                PoseStack local = new PoseStack();
                local.last().pose().set(pose.pose());
                local.last().normal().set(pose.normal());
                QuadInstance quad = new QuadInstance();
                for (Cell cell : draw) {
                    local.pushPose();
                    local.translate(cell.position().getX(), cell.position().getY(), cell.position().getZ());
                    emit(consumer, local.last(), cell.quads(), cell.state(),
                        cell.light(), cell.overlay(), quad);
                    local.popPose();
                }
            });
        }
    }
    //?}

    //? if >=26.1 {
    private static void emit(VertexConsumer consumer, PoseStack.Pose pose, List<BakedQuad> quads,
        BlockState state, int light, int overlay) {
        emit(consumer, pose, quads, state, light, overlay, new QuadInstance());
    }

    private static void emit(VertexConsumer consumer, PoseStack.Pose pose, List<BakedQuad> quads,
        BlockState state, int light, int overlay, QuadInstance properties) {
        properties.setLightCoords(light);
        properties.setOverlayCoords(overlay);
        BlockColors colors = Minecraft.getInstance().getBlockColors();
        for (BakedQuad quad : quads) {
            int tintIndex = quad.materialInfo().tintIndex();
            int color = -1;
            if (tintIndex >= 0) {
                BlockTintSource tint = colors.getTintSource(state, tintIndex);
                if (tint != null) color = 0xFF000000 | tint.color(state);
            }
            properties.setColor(color);
            consumer.putBakedQuad(pose, quad, properties);
        }
    }
    //?}

    /** Engines use 3D item models, aligned to the orientation captured in the blueprint. */
    public static float[] engineItemRotation(BlockState state) {
        if (!(state.getBlock() instanceof BlockEngineBase_BC8)
            || !state.hasProperty(BuildCraftProperties.BLOCK_FACING_6)) return null;
        return switch (state.getValue(BuildCraftProperties.BLOCK_FACING_6)) {
            case DOWN -> new float[] { 180f, 0f };
            case NORTH -> new float[] { 90f, 180f };
            case SOUTH -> new float[] { 90f, 0f };
            case WEST -> new float[] { 90f, 90f };
            case EAST -> new float[] { 90f, 270f };
            default -> new float[] { 0f, 0f };
        };
    }
}
