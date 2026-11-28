/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.client.render.preview;

import buildcraft.fabric.mixin.GuiGraphicsStateAccess;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
import org.joml.Matrix3x2f;

/** Fabric uses the vanilla GUI extraction queue, like the blueprint preview. */
final class RobotPreviewSubmission {
    private RobotPreviewSubmission() {}
    static void submit(GuiGraphicsExtractor graphics, TrackingItemStackRenderState model,
                       int cx, int cy, int size) {
        int x0 = cx - size / 2, y0 = cy - size / 2;
        int x1 = x0 + size, y1 = y0 + size;
        var pose = new Matrix3x2f(graphics.pose());
        var state = new RobotPreviewRenderState(model, x0, y0, x1, y1, size, pose, null,
            PictureInPictureRenderState.getBounds(x0, y0, x1, y1, null));
        ((GuiGraphicsStateAccess) graphics).bcce$getGuiRenderState().addPicturesInPictureState(state);
    }
}
