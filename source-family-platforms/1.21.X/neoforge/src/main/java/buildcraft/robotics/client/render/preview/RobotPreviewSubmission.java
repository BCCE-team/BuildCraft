//? source if >=1.21.11
/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.client.render.preview;

import buildcraft.robotics.BCRobotics;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;*/
//?}
//? if >=26.2 {
import org.joml.Matrix3x2f;
//?}

/** NeoForge's physical-client registration and custom picture-in-picture submission API. */
@EventBusSubscriber(modid = BCRobotics.MODID, value = Dist.CLIENT)
public final class RobotPreviewSubmission {
    private RobotPreviewSubmission() {}

    @SubscribeEvent
    public static void registerRobotPreview(RegisterPictureInPictureRenderersEvent event) {
        event.register(RobotPreviewRenderState.class, RobotPreviewPipRenderer::new);
    }

    //? if >=26.1 {
    static void submit(GuiGraphicsExtractor graphics, TrackingItemStackRenderState model,
    //?} else {
    /*static void submit(GuiGraphics graphics, TrackingItemStackRenderState model,*/
    //?}
                       int cx, int cy, int size) {
        int x0 = cx - size / 2, y0 = cy - size / 2;
        int x1 = x0 + size, y1 = y0 + size;
        var scissor = graphics.peekScissorStack();
        //? if >=26.2 {
        var pose = new Matrix3x2f(graphics.pose());
        var state = new RobotPreviewRenderState(model, x0, y0, x1, y1, size, pose, scissor,
            PictureInPictureRenderState.getBounds(x0, y0, x1, y1, pose, scissor));
        //?} else {
        /*var state = new RobotPreviewRenderState(model, x0, y0, x1, y1, size, scissor,
            PictureInPictureRenderState.getBounds(x0, y0, x1, y1, scissor));*/
        //?}
        graphics.submitPictureInPictureRenderState(state);
    }
}
