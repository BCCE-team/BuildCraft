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
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.AABB;

public class RenderFiller implements LegacyBlockEntityRenderer<TileFiller>, LegacyLaserBlockEntityRenderer<TileFiller> {
    public RenderFiller(BlockEntityRendererProvider.Context context) {
    }

    public void render(TileFiller tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer,
        int light, int overlay) {
        renderLasers(tile, partialTicks, matrix, buffer, light, overlay);
    }

    public void submit(LegacyRenderState<TileFiller> state, PoseStack matrix, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        LegacyBlockEntityRenderer.super.submit(state, matrix, collector, cameraState);
        TileFiller tile = state.blockEntity();
        if (tile == null || tile.getLevel() == null || tile.getBuilder() == null) return;
        matrix.pushPose();
        RenderSnapshotBuilder.submit(
            tile.getBuilder(), tile.getLevel(), tile.getBlockPos(), state.partialTick(), matrix, collector
        );
        matrix.popPose();
    }

    public void renderLasers(TileFiller tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer,
        int light, int overlay) {
        if (!tile.markerBox) return;
        VertexConsumer bb = buffer.getBuffer(RenderCompat.cutout());
        matrix.pushPose();
        matrix.translate(-tile.getBlockPos().getX(), -tile.getBlockPos().getY(), -tile.getBlockPos().getZ());
        Matrix4f boxPose = matrix.last().pose();
        Matrix3f boxNormal = matrix.last().normal();
        LaserBoxRenderer.renderLaserBoxDynamic(tile.box, BuildCraftLaserManager.STRIPES_WRITE, boxPose, boxNormal, bb, true);
        matrix.popPose();
    }

    public boolean shouldRenderOffScreen(TileFiller tile) { return true; }
    public AABB getRenderBoundingBox(TileFiller tile) { return tile.getRenderBoundingBox(); }
    public int getViewDistance() { return 256; }
}
