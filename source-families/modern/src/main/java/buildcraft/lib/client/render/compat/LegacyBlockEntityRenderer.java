//? source if >=1.21.11
package buildcraft.lib.client.render.compat;

import javax.annotation.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.lib.client.render.laser.LegacyLaserBlockEntityRenderer;
import buildcraft.lib.compat.RenderCompat;
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
 * 1.21.11 bridge for BCCE's pre-submit-model block entity renderers.
 *
 * <p>The compatibility render entry point preserves source compatibility. Laser-capable renderers additionally expose a
 * laser-only pass via {@link LegacyLaserBlockEntityRenderer}; that pass is submitted as custom geometry so dynamic
 * BuildCraft lasers continue to use Minecraft's 1.21.11 render queue instead of the removed immediate renderer.</p>
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

        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    };

    void render(T blockEntity, float partialTicks, PoseStack poseStack, MultiBufferSource bufferSource,
        int combinedLight, int combinedOverlay);

    default LegacyRenderState<T> createRenderState() {
        return new LegacyRenderState<>();
    }

    default void extractRenderState(T blockEntity, LegacyRenderState<T> renderState, float partialTick,
        Vec3 cameraPosition, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        BlockEntityRenderState.extractBase(blockEntity, renderState, crumblingOverlay);
        renderState.blockEntity = blockEntity;
        renderState.partialTick = partialTick;
    }

    @SuppressWarnings("unchecked")
    default void submit(LegacyRenderState<T> renderState, PoseStack poseStack, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        if (renderState.blockEntity == null || !(this instanceof LegacyLaserBlockEntityRenderer<?> rawLaserRenderer)) {
            return;
        }

        LegacyLaserBlockEntityRenderer<T> laserRenderer = (LegacyLaserBlockEntityRenderer<T>) rawLaserRenderer;
        submitLaserLayer(renderState, poseStack, collector, laserRenderer, RenderCompat.cutout());
        submitLaserLayer(renderState, poseStack, collector, laserRenderer, RenderCompat.solid());
    }

    private static <T extends BlockEntity> void submitLaserLayer(LegacyRenderState<T> renderState, PoseStack poseStack,
        SubmitNodeCollector collector, LegacyLaserBlockEntityRenderer<T> laserRenderer, RenderType renderType) {
        collector.submitCustomGeometry(poseStack, renderType, (pose, consumer) -> {
            PoseStack legacyPose = new PoseStack();
            legacyPose.last().pose().set(pose.pose());
            legacyPose.last().normal().set(pose.normal());

            MultiBufferSource filteredBuffers = requestedType ->
                requestedType == renderType || requestedType.equals(renderType) ? consumer : DISCARDING_VERTEX_CONSUMER;

            laserRenderer.renderLasers(renderState.blockEntity, renderState.partialTick, legacyPose, filteredBuffers,
                renderState.lightCoords, OverlayTexture.NO_OVERLAY);
        });
    }

    default boolean shouldRenderOffScreen() {
        return this instanceof LegacyLaserBlockEntityRenderer<?>;
    }

    final class LegacyRenderState<T extends BlockEntity> extends BlockEntityRenderState {
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
