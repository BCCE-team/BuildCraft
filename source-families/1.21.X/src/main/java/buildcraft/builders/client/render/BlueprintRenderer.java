//? source if >=1.21.10
/* Copyright (c) 2026 BuildCraft Community Edition contributors
 */
package buildcraft.builders.client.render;

import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.client.render.pip.BlueprintPipRenderState;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?}

/** Renders a Blueprint or Template in a clipped, steadily rotating 3D viewport. */
public final class BlueprintRenderer {
    private BlueprintRenderer() {}

    //? if >=26.1 {
    public static void renderSnapshot(GuiGraphicsExtractor graphics, Snapshot snapshot,
    //?} else {
    /*public static void renderSnapshot(GuiGraphics graphics, Snapshot snapshot,*/
    //?}
        int x, int y, int width, int height) {
        if (graphics == null || snapshot == null || snapshot.size == null || width <= 0 || height <= 0) return;
        long sx = Math.max(1, snapshot.size.getX());
        long sy = Math.max(1, snapshot.size.getY());
        long sz = Math.max(1, snapshot.size.getZ());
        float diagonal = (float)Math.sqrt((double)sx*sx+(double)sy*sy+(double)sz*sz);
        float scale = Math.min(width,height)/(diagonal * 1.05f);
        graphics.submitPictureInPictureRenderState(new BlueprintPipRenderState(
            snapshot,x,y,x+width,y+height,scale,graphics.peekScissorStack()));
    }
}
