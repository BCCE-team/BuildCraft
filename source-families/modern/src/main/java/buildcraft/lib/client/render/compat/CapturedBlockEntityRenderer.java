//? source if >=1.21.11
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.client.render.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Geometry-capture adapter for machine renderers. Geometry is evaluated ONCE while
 * extracting the state, with the original per-layer buffers. Submission only replays an
 * immutable snapshot: it never accesses a live tile, its animation variables, or a world.
 *
 * This is deliberately separate from the laser-only legacy bridge. Enabling render() on
 * that bridge would draw lasers/items twice for builders that already submit native layers.
 */
public interface CapturedBlockEntityRenderer<T extends BlockEntity>
    extends BlockEntityRenderer<T, CapturedBlockEntityRenderer.GeometryState> {

    void renderLegacy(T tile, float partialTick, PoseStack pose, MultiBufferSource buffers, int light, int overlay);

    default GeometryState createRenderState() {
        return new GeometryState();
    }

    default void extractRenderState(T tile, GeometryState state, float partialTick, Vec3 cameraPosition,
        @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(tile, state, crumblingOverlay);
        state.layers = List.of();
        if (tile.getLevel() == null || tile.isRemoved()) {
            return;
        }
        RecordingBuffers buffers = new RecordingBuffers();
        renderLegacy(tile, partialTick, new PoseStack(), buffers, state.lightCoords, OverlayTexture.NO_OVERLAY);
        state.layers = buffers.finish();
    }

    default void submit(GeometryState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        for (Layer layer : state.layers) {
            collector.submitCustomGeometry(pose, layer.type(), (transform, target) -> {
                for (Vertex vertex : layer.vertices()) {
                    vertex.emit(transform, target);
                }
            });
        }
    }

    final class GeometryState extends BlockEntityRenderState {
        private List<Layer> layers = List.of();
    }

    record Layer(RenderType type, List<Vertex> vertices) {}

    record Vertex(float x, float y, float z, int r, int g, int b, int a, float u, float v,
        int overlayU, int overlayV, int lightU, int lightV, float nx, float ny, float nz, float width) {
        void emit(PoseStack.Pose pose, VertexConsumer target) {
            target.addVertex(pose.pose(), x, y, z).setColor(r, g, b, a).setUv(u, v)
                .setUv1(overlayU, overlayV).setUv2(lightU, lightV).setNormal(pose, nx, ny, nz)
                .setLineWidth(width);
        }
    }

    final class RecordingBuffers implements MultiBufferSource {
        private final Map<RenderType, RecordingConsumer> layers = new LinkedHashMap<>();

        public VertexConsumer getBuffer(RenderType type) {
            return layers.computeIfAbsent(type, ignored -> new RecordingConsumer());
        }

        List<Layer> finish() {
            List<Layer> result = new ArrayList<>();
            layers.forEach((type, consumer) -> {
                consumer.endVertex();
                if (!consumer.vertices.isEmpty()) {
                    result.add(new Layer(type, List.copyOf(consumer.vertices)));
                }
            });
            return List.copyOf(result);
        }
    }

    final class RecordingConsumer implements VertexConsumer {
        private final List<Vertex> vertices = new ArrayList<>();
        private boolean active;
        private float x, y, z, u, v, nx, ny, nz, width;
        private int r, g, b, a, overlayU, overlayV, lightU, lightV;

        private void endVertex() {
            if (active) {
                vertices.add(new Vertex(x, y, z, r, g, b, a, u, v, overlayU, overlayV, lightU, lightV,
                    nx, ny, nz, width));
                active = false;
            }
        }

        public VertexConsumer addVertex(float x, float y, float z) {
            endVertex();
            this.x = x; this.y = y; this.z = z;
            r = g = b = a = 255;
            u = v = nx = nz = 0; ny = 1; width = 1;
            overlayU = overlayV = lightU = lightV = 0;
            active = true;
            return this;
        }
        public VertexConsumer setColor(int r, int g, int b, int a) {
            this.r = r; this.g = g; this.b = b; this.a = a; return this;
        }
        public VertexConsumer setColor(int color) {
            return setColor((color >>> 16) & 255, (color >>> 8) & 255, color & 255, (color >>> 24) & 255);
        }
        public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
        public VertexConsumer setUv1(int u, int v) { overlayU = u; overlayV = v; return this; }
        public VertexConsumer setUv2(int u, int v) { lightU = u; lightV = v; return this; }
        public VertexConsumer setNormal(float x, float y, float z) { nx = x; ny = y; nz = z; return this; }
        public VertexConsumer setLineWidth(float width) { this.width = width; return this; }
    }
}
