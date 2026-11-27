//? source if >=1.21.11
/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.client.render.preview;

import buildcraft.lib.compat.minecraft.gui.BCGraphics;
import buildcraft.robotics.BCRoboticsBoards;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;*/
//?}

/** Side-on list portraits and a real, animated detail model; no GUI item transform. */
public final class RobotProcessorPreview {
    private RobotProcessorPreview() {}

    //? if >=26.1 {
    public static void render(GuiGraphicsExtractor graphics, ItemStack stack,
    //?} else {
    /*public static void render(GuiGraphics graphics, ItemStack stack,*/
    //?}
                              int cx, int cy, int pixelSize, boolean rotate) {
        if (stack.isEmpty()) return;
        if (!rotate) {
            // The original model is an 8/16-wide cube. With yaw=180 the north
            // face is visible: UV (4,4)..(8,8) in the model's 16-unit UV space,
            // i.e. (8,8)..(16,16) on the original 32x32 profession texture.
            String name = BCRoboticsBoards.getRobotBoard(stack).robotTexture();
            Identifier texture = Identifier.fromNamespaceAndPath("buildcraftrobotics",
                "textures/entities/" + name + "_active.png");
            int side = pixelSize / 2;
            BCGraphics.blit(graphics, texture, cx - side / 2, cy - side / 2,
                8, 8, side, side, 8, 8, 32, 32);
            return;
        }
        // Capture the real board-dependent baked item geometry during GUI extraction.
        // NONE is essential: GUI would add the inventory's fixed 30/225-degree tilt.
        var minecraft = Minecraft.getInstance();
        var model = new TrackingItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(model, stack,
            ItemDisplayContext.NONE, minecraft.level, null, 0);
        RobotPreviewSubmission.submit(graphics, model, cx, cy, pixelSize);
    }
}
