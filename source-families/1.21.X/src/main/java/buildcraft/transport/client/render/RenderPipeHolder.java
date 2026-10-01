//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.client.render;

import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import buildcraft.lib.compat.RenderCompat;
import buildcraft.transport.client.PipeRegistryClient;
import buildcraft.transport.internal.pipe.IPipeBehaviourRenderer;
import buildcraft.transport.internal.pipe.IPipeFlowRenderer;
import buildcraft.transport.internal.pipe.PipeBehaviour;
import buildcraft.transport.internal.pipe.PipeFlow;
import buildcraft.transport.internal.pluggable.IPlugDynamicRenderer;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import buildcraft.transport.tile.TilePipeHolder;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Native 1.21.11 pipe block-entity renderer.
 *
 * <p>1.21.11 no longer invokes the pre-submit BER {@code render(...)} method. Only genuinely dynamic pipe geometry
 * is submitted here. The pipe body and connection arms are rendered by {@code ModelPipeNative121111} through the
 * normal chunk/terrain model pipeline so they participate in depth, terrain lighting and shader reflection passes.</p>
 */
public class RenderPipeHolder implements BlockEntityRenderer<TilePipeHolder, RenderPipeHolder.PipeRenderState> {
    /**
     * Sink used by the 1.21.11 legacy-render bridge when a renderer asks for a layer other than the
     * layer currently being submitted. VertexMultiConsumer.create() is not a valid empty consumer in
     * 1.21.11: the zero-argument overload deliberately throws IllegalArgumentException.
     */
    private static final VertexConsumer DISCARDING_VERTEX_CONSUMER = new VertexConsumer() {
        public VertexConsumer addVertex(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        public VertexConsumer setColor(int color) {
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    };
    public static final Direction[] renderFacing = {
        Direction.UP, Direction.NORTH, Direction.WEST, Direction.SOUTH, Direction.EAST, Direction.DOWN
    };
    public static final int[] CENTER_UV = { 4, 12, 4, 12 };
    public static final int[] EAST_UV = { 0, 8, 4, 16 };
    public static final int[] WEST_UV = { 0, 8, 4, 16 };
    public static final int[] SOUTH_UV = { 0, 8, 4, 16 };
    public static final int[] NORTH_UV = { 0, 8, 4, 16 };
    public static final int[] UP_UV = { 4, 12, 0, 4 };
    public static final int[] DOWN_UV = { 4, 12, 12, 16 };

    public RenderPipeHolder(BlockEntityRendererProvider.Context ctx) {
    }

    public PipeRenderState createRenderState() {
        return new PipeRenderState();
    }

    public void extractRenderState(TilePipeHolder tile, PipeRenderState state, float partialTick, Vec3 cameraPosition,
        @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(tile, state, crumblingOverlay);

        state.tile = tile;
        state.partialTick = partialTick;

        // Static pipe and pluggable geometry is owned by ModelPipeNative121111.
        // Do not cache it here as BER geometry as well: submitting the same facade twice produces
        // camera-dependent moire/z-fighting stripes on the facade surface.
    }

    public void submit(PipeRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        TilePipeHolder tile = state.tile;
        if (tile == null) {
            return;
        }

        Pipe pipe = tile.getPipe();
        if (pipe == null || pipe == Pipe.EMPTY) {
            return;
        }

        final int light = state.lightCoords;
        final int overlay = OverlayTexture.NO_OVERLAY;

        // Wires are generated dynamically from the wire graph, so they cannot live in the static pipe model cache.
        submitLegacyGeometry(collector, poseStack, RenderCompat.cutout(), (matrix, buffers) ->
            PipeWireRenderer.renderWires(tile, 0, matrix, buffers, light, overlay)
        );

        // Static pluggables are already part of ModelPipeNative121111. Only dynamic renderers remain here
        // (for example the animated portions of gates and pulsars). Run them through layer-filtered buffers.
        submitLegacyGeometry(collector, poseStack, RenderCompat.cutout(), (matrix, buffers) ->
            renderPluggables(tile, state.partialTick, matrix, buffers, light, overlay)
        );
        submitLegacyGeometry(collector, poseStack, RenderCompat.translucent(), (matrix, buffers) ->
            renderPluggables(tile, state.partialTick, matrix, buffers, light, overlay)
        );

        if (pipe.flow instanceof PipeFlowItems itemFlow) {
            // ItemStack rendering itself moved to ItemStackRenderState in 1.21.11 and cannot be tunneled through a
            // VertexConsumer-only custom-geometry callback. Use the native submission path for travelling items.
            PipeFlowRendererItems.INSTANCE.submit(itemFlow, state.partialTick, poseStack, collector, light, overlay);
        } else if (pipe.flow instanceof PipeFlowFluids fluidFlow) {
            // Fluids need their own transparent submit. Sending them through a cutout-only compatibility bridge can
            // discard the atlas geometry in 1.21.11, which made filled pipes look completely empty.
            PipeFlowRendererFluids.INSTANCE.submit(fluidFlow, state.partialTick, poseStack, collector, light, overlay);
        } else if (pipe.flow != null) {
            submitLegacyGeometry(collector, poseStack, RenderCompat.cutout(), (matrix, buffers) ->
                renderFlow(pipe.flow, state.partialTick, matrix, buffers, light, overlay)
            );
        }

        if (pipe.behaviour != null) {
            submitLegacyGeometry(collector, poseStack, RenderCompat.cutout(), (matrix, buffers) ->
                renderBehaviour(pipe.behaviour, state.partialTick, matrix, buffers, light, overlay)
            );
        }
    }

    /**
     * Bridges the remaining BuildCraft direct-VertexConsumer renderers into 1.21.11's submit phase.
     *
     * <p>The collector owns the actual buffer. A tiny layer-filtering MultiBufferSource lets legacy renderers keep
     * requesting their declared render type without allowing another layer to write into this submission.</p>
     */
    private static void submitLegacyGeometry(SubmitNodeCollector collector, PoseStack poseStack, RenderType renderType,
        BiConsumer<PoseStack, MultiBufferSource> renderer) {
        collector.submitCustomGeometry(poseStack, renderType, (pose, consumer) -> {
            PoseStack legacyPose = copyPose(pose);
            MultiBufferSource legacyBuffers = requested -> sameLayer(requested, renderType)
                ? consumer
                : DISCARDING_VERTEX_CONSUMER;
            renderer.accept(legacyPose, legacyBuffers);
        });
    }

    private static PoseStack copyPose(PoseStack.Pose source) {
        PoseStack copy = new PoseStack();
        copy.last().pose().set(source.pose());
        copy.last().normal().set(source.normal());
        return copy;
    }

    private static boolean sameLayer(RenderType left, RenderType right) {
        return left == right || (left != null && left.equals(right));
    }

    private static void renderPluggables(TilePipeHolder pipe, float partialTicks, PoseStack matrix,
        MultiBufferSource buffer, int combinedLight, int combinedOverlay) {
        for (Direction face : Direction.values()) {
            PipePluggable plug = pipe.getPluggable(face);
            if (plug == PipePluggable.EMPTY) {
                continue;
            }
            renderPlug(plug, partialTicks, matrix, buffer, combinedLight, combinedOverlay);
        }
    }

    private static <P extends PipePluggable> void renderPlug(P plug, float partialTicks, PoseStack matrix,
        MultiBufferSource buffer, int combinedLight, int combinedOverlay) {
        IPlugDynamicRenderer<P> renderer = PipeRegistryClient.getPlugRenderer(plug);
        if (renderer != null) {
            renderer.render(plug, partialTicks, matrix, buffer, combinedLight, combinedOverlay);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void renderFlow(PipeFlow flow, float partialTicks, PoseStack matrix, MultiBufferSource buffer,
        int combinedLight, int combinedOverlay) {
        IPipeFlowRenderer renderer = PipeRegistryClient.getFlowRenderer(flow);
        if (renderer != null) {
            renderer.render(flow, partialTicks, matrix, buffer, combinedLight, combinedOverlay);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void renderBehaviour(PipeBehaviour behaviour, float partialTicks, PoseStack matrix,
        MultiBufferSource buffer, int combinedLight, int combinedOverlay) {
        IPipeBehaviourRenderer renderer = PipeRegistryClient.getBehaviourRenderer(behaviour);
        if (renderer != null) {
            renderer.render(behaviour, partialTicks, matrix, buffer, combinedLight, combinedOverlay);
        }
    }

    public static class PipeRenderState extends BlockEntityRenderState {
        @Nullable
        TilePipeHolder tile;
        float partialTick;
    }
}
