//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;
//? } else {
import net.minecraft.resources.ResourceLocation;
//? }

/** Small GUI facade. PoseStack/Matrix3x2fStack and render pipelines are backend details. */
public final class BCGraphics {
    private BCGraphics() {}
    public static void text(GuiGraphics g, Font font, String text, int x, int y, int color, boolean shadow) {
        g.drawString(font, text, x, y, color, shadow);
    }
    public static void text(GuiGraphics g, Font font, Component text, int x, int y, int color, boolean shadow) {
        g.drawString(font, text, x, y, color, shadow);
    }
    public static void text(GuiGraphics g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        g.drawString(font, text, x, y, color, shadow);
    }
    public static void scissor(GuiGraphics g, int left, int top, int right, int bottom) {
        g.enableScissor(left, top, right, bottom);
    }
    public static void endScissor(GuiGraphics g) { g.disableScissor(); }
    public static void fill(GuiGraphics g, int left, int top, int right, int bottom, int color) {
        g.fill(left, top, right, bottom, color);
    }
    public static void gradient(GuiGraphics g, int left, int top, int right, int bottom, int start, int end) {
        g.fillGradient(left, top, right, bottom, start, end);
    }
//? if >=1.21.11 {
    public static void push(GuiGraphics g) { g.pose().pushMatrix(); }
    public static void pop(GuiGraphics g) { g.pose().popMatrix(); }
    public static void translate(GuiGraphics g, float x, float y) { g.pose().translate(x, y); }
    public static void scale(GuiGraphics g, float x, float y) { g.pose().scale(x, y); }
    public static void nextLayer(GuiGraphics g) { g.nextStratum(); }

    public static void blit(GuiGraphics g, Identifier texture, int x, int y, int u, int v, int width, int height) {
        blit(g, texture, x, y, u, v, width, height, 256, 256);
    }
    public static void blit(GuiGraphics g, Identifier texture, int x, int y, int u, int v,
        int width, int height, int texWidth, int texHeight) {
        blit(g, texture, x, y, u, v, width, height, width, height, texWidth, texHeight);
    }
    public static void blit(GuiGraphics g, Identifier texture, int x, int y, int u, int v,
        int width, int height, int sourceWidth, int sourceHeight, int texWidth, int texHeight) {
        if (g == null || texture == null || width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, (float) u, (float) v,
            width, height, sourceWidth, sourceHeight, texWidth, texHeight);
    }
//? } else {
    public static void push(GuiGraphics g) { g.pose().pushPose(); }
    public static void pop(GuiGraphics g) { g.pose().popPose(); }
    public static void translate(GuiGraphics g, float x, float y) { g.pose().translate(x, y, 0); }
    public static void scale(GuiGraphics g, float x, float y) { g.pose().scale(x, y, 1); }
    public static void nextLayer(GuiGraphics g) { /* The immediate backend is ordered by submission. */ }

    public static void blit(GuiGraphics g, ResourceLocation texture, int x, int y, int u, int v, int width, int height) {
        blit(g, texture, x, y, u, v, width, height, 256, 256);
    }
    public static void blit(GuiGraphics g, ResourceLocation texture, int x, int y, int u, int v,
        int width, int height, int texWidth, int texHeight) {
        blit(g, texture, x, y, u, v, width, height, width, height, texWidth, texHeight);
    }
    public static void blit(GuiGraphics g, ResourceLocation texture, int x, int y, int u, int v,
        int width, int height, int sourceWidth, int sourceHeight, int texWidth, int texHeight) {
        if (g == null || texture == null || width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
        g.blit(texture, x, y, width, height, (float) u, (float) v, sourceWidth, sourceHeight, texWidth, texHeight);
    }
//? }
}
