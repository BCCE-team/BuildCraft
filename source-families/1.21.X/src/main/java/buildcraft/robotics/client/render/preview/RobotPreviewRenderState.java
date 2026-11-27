//? source if >=1.21.11
/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.client.render.preview;

import javax.annotation.Nullable;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
//? if >=26.1 {
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
//?} else {
/*import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;*/
//?}
//? if >=26.2 {
import org.joml.Matrix3x2fc;
//?}

/** A single selected robot's baked item model in an offscreen GUI viewport. */
public record RobotPreviewRenderState(
    TrackingItemStackRenderState model,
    int x0, int y0, int x1, int y1,
    float scale,
    //? if >=26.2 {
    Matrix3x2fc pose,
    //?}
    @Nullable ScreenRectangle scissorArea,
    @Nullable ScreenRectangle bounds
) implements PictureInPictureRenderState {}
