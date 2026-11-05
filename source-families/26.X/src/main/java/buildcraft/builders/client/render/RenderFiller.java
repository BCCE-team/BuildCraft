//? source if >=1.21.11
/* Copyright (c) 2017 SpaceToad and the BuildCraft team */
package buildcraft.builders.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import buildcraft.builders.tile.TileFiller;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.compat.LegacyBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LegacyLaserBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.compat.RenderCompat;
//? if >=26.2 {
import buildcraft.lib.compat.minecraft.render.BCVertexBuffers;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;*/
//?}
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;

public class RenderFiller implements LegacyBlockEntityRenderer<TileFiller>, LegacyLaserBlockEntityRenderer<TileFiller> {
    private final BuilderIndicatorLights indicatorLights = new BuilderIndicatorLights();

    public RenderFiller(BlockEntityRendererProvider.Context context) {
    }

    //? if >=26.2 {
    public void render(TileFiller tile, float partialTicks, PoseStack matrix, BCVertexBuffers buffer,
    //?} else {
    /*public void render(TileFiller tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer,*/
    //?}
        int light, int overlay) {
        renderLasers(tile, partialTicks, matrix, buffer, light, overlay);
    }

    public void submit(LegacyRenderState<TileFiller> state, PoseStack matrix, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        LegacyBlockEntityRenderer.super.submit(state, matrix, collector, cameraState);
        TileFiller tile = state.blockEntity();
        // The preview cubes are vertex-coloured translucent geometry, so submit them on the
        // generic translucent pass instead of a scan-textured entity layer.
        if (tile != null && tile.isPreviewEnabled() && tile.getLevel() != null) {
            collector.submitCustomGeometry(matrix, RenderCompat.translucent(), (pose, consumer) -> {
                PoseStack ghostPose = new PoseStack();
                ghostPose.last().pose().set(pose.pose());
                ghostPose.last().normal().set(pose.normal());
                FillerGhostPreview.render(tile, ghostPose, consumer);
            });
        }
        if (tile == null || tile.getLevel() == null || tile.getBuilder() == null) return;
        matrix.pushPose();
        RenderSnapshotBuilder.submitFiller(
            tile.getBuilder(), tile.getLevel(), tile.getBlockPos(), state.partialTick(), matrix, collector
        );
        matrix.popPose();
    }

    //? if >=26.2 {
    public void renderLasers(TileFiller tile, float partialTicks, PoseStack matrix, BCVertexBuffers buffer,
    //?} else {
    /*public void renderLasers(TileFiller tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer,*/
    //?}
        int light, int overlay) {
        if (tile.getLevel() == null) return;
        VertexConsumer bb = buffer.getBuffer(RenderCompat.cutout());
        indicatorLights.drawFiller(tile, matrix.last().pose(), matrix.last().normal(), bb);
        if (!tile.markerBox && tile.getBuilder() == null) return;
        matrix.pushPose();
        matrix.translate(-tile.getBlockPos().getX(), -tile.getBlockPos().getY(), -tile.getBlockPos().getZ());
        Matrix4f boxPose = matrix.last().pose();
        Matrix3f boxNormal = matrix.last().normal();
        if (tile.markerBox) {
            LaserBoxRenderer.renderLaserBoxDynamic(tile.box, BuildCraftLaserManager.STRIPES_WRITE, boxPose, boxNormal, bb, true);
        }
        FillerWorkRenderer.render(tile.getBuilder(), tile.getLevel().getGameTime(), partialTicks,
                boxPose, boxNormal, bb);
        matrix.popPose();
    }

    public boolean shouldRenderOffScreen(TileFiller tile) { return true; }
    public AABB getRenderBoundingBox(TileFiller tile) { return tile.getRenderBoundingBox(); }
    public int getViewDistance() { return 256; }
}

