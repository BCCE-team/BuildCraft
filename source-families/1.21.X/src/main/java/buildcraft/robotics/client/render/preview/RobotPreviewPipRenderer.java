//? source if >=1.21.11
/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.client.render.preview;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
//? if >=26.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;*/
//?}

/** Restore the 1.19.2 NONE-context, full-bright, rotating robot preview. */
public final class RobotPreviewPipRenderer extends PictureInPictureRenderer<RobotPreviewRenderState> {
    private static final int FULL_BRIGHT = 15728880;

    //? if >=26.2 {
    public RobotPreviewPipRenderer() { super(); }
    //?} else {
    /*public RobotPreviewPipRenderer(MultiBufferSource.BufferSource buffers) { super(buffers); }*/
    //?}

    @Override
    public Class<RobotPreviewRenderState> getRenderStateClass() {
        return RobotPreviewRenderState.class;
    }

    @Override
    protected String getTextureLabel() { return "bcce_robot_processor_preview"; }

    @Override
    protected float getTranslateY(int height, int guiScale) { return height / 2.0F; }

    //? if >=26.2 {
    @Override
    protected void renderToTexture(RobotPreviewRenderState state, PoseStack poseStack,
                                   SubmitNodeCollector collector) {
    //?} else {
    /*@Override
    protected void renderToTexture(RobotPreviewRenderState state, PoseStack poseStack) {*/
    //?}
        var gameRenderer = Minecraft.getInstance().gameRenderer;
        // PiP supplies the viewport centre and scale; restore the legacy y-up item pose.
        poseStack.pushPose();
        try {
            poseStack.scale(1.0F, -1.0F, -1.0F);
            float yaw = 180.0F + (System.currentTimeMillis() % 12000L) * 0.03F;
            //? if >=26.3 {
            poseStack.rotate(Axis.YP.rotationDegrees(yaw));
            //?} else {
            /*poseStack.mulPose(Axis.YP.rotationDegrees(yaw));*/
            //?}
            //? if >=26.2 {
            // The PiP backend draws submitted geometry after this callback. As in
            // vanilla's oversized item renderer, set lighting before submitting it.
            gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_FLAT);
            state.model().submit(poseStack, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
            //?} else {
            /*var lighting = gameRenderer.getLighting();
            lighting.setupFor(Lighting.Entry.ITEMS_FLAT);
            try {
                FeatureRenderDispatcher dispatcher = gameRenderer.getFeatureRenderDispatcher();
                SubmitNodeStorage storage = dispatcher.getSubmitNodeStorage();
                state.model().submit(poseStack, storage, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0);
                dispatcher.renderAllFeatures();
                this.bufferSource.endBatch();
            } finally {
                lighting.setupFor(Lighting.Entry.ITEMS_3D);
            }*/
            //?}
        } finally {
            poseStack.popPose();
        }
    }
}
