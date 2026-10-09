//? source if >=26.3
package buildcraft.lib.client.render.compat;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.lib.client.render.laser.LegacyLaserBlockEntityRenderer;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.compat.minecraft.render.BCVertexBuffers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Adapter for BCCE's direct block-entity renderers on the submit-model pipeline.
 *
 * <p>The compatibility render entry point preserves source compatibility. Laser-capable renderers additionally expose a
 * laser-only pass via {@link LegacyLaserBlockEntityRenderer}; that pass is submitted as custom geometry so dynamic
 * BuildCraft lasers use the render queue instead of the removed immediate renderer.</p>
 */
public interface LegacyBlockEntityRenderer<T extends BlockEntity>
    extends BlockEntityRenderer<T, LegacyBlockEntityRenderer.LegacyRenderState<T>> {

    VertexConsumer DISCARDING_VERTEX_CONSUMER = new VertexConsumer() {
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

        public VertexConsumer setUv3(float u, float v) { return this; }

        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    };

    void render(T blockEntity, float partialTicks, PoseStack poseStack, BCVertexBuffers bufferSource,
        int combinedLight, int combinedOverlay);

    default LegacyRenderState<T> createRenderState() {
        return new LegacyRenderState<>();
    }

    default void extractRenderState(T blockEntity, LegacyRenderState<T> renderState, float partialTick,
        Vec3 cameraPosition, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(blockEntity, renderState, crumblingOverlay);
        renderState.blockEntity = blockEntity;
        renderState.partialTick = partialTick;
        renderState.layers = java.util.List.of();
        if (blockEntity.getLevel() == null || blockEntity.isRemoved()
            || !(this instanceof LegacyLaserBlockEntityRenderer<?> raw)) return;
        @SuppressWarnings("unchecked")
        LegacyLaserBlockEntityRenderer<T> lasers = (LegacyLaserBlockEntityRenderer<T>) raw;
        CapturedBlockEntityRenderer.RecordingBuffers buffers = new CapturedBlockEntityRenderer.RecordingBuffers();
        BCVertexBuffers filtered = type -> type.equals(RenderCompat.cutout()) || type.equals(RenderCompat.solid())
            ? buffers.getBuffer(type) : DISCARDING_VERTEX_CONSUMER;
        try (BCWorldGeometry.Scope ignored = BCWorldGeometry.bind(buffers)) {
            lasers.renderLasers(blockEntity, partialTick, new PoseStack(), filtered,
                renderState.lightCoords, OverlayTexture.NO_OVERLAY);
        }
        renderState.layers = buffers.finish();
    }

    default void submit(LegacyRenderState<T> renderState, PoseStack poseStack, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        BCWorldGeometry.submit(renderState.layers, poseStack, collector);
    }

    default boolean shouldRenderOffScreen() {
        return this instanceof LegacyLaserBlockEntityRenderer<?>;
    }

    final class LegacyRenderState<T extends BlockEntity> extends BlockEntityRenderState {
        private java.util.List<CapturedBlockEntityRenderer.Layer> layers = java.util.List.of();
        private T blockEntity;
        private float partialTick;

        public T blockEntity() {
            return blockEntity;
        }

        public float partialTick() {
            return partialTick;
        }
    }
}
