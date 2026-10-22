package buildcraft.lib.compat.minecraft.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;

/** Small GUI facade. PoseStack/Matrix3x2fStack and render pipelines are backend details. */
public final class BCGraphics {
    private BCGraphics() {}
    public static void text(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, boolean shadow) {
        g.text(font, text, x, y, color, shadow);
    }
    public static void text(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color, boolean shadow) {
        g.text(font, text, x, y, color, shadow);
    }
    public static void text(GuiGraphicsExtractor g, Font font, FormattedCharSequence text, int x, int y, int color, boolean shadow) {
        g.text(font, text, x, y, color, shadow);
    }
    public static void scissor(GuiGraphicsExtractor g, int left, int top, int right, int bottom) {
        g.enableScissor(left, top, right, bottom);
    }
    public static void endScissor(GuiGraphicsExtractor g) { g.disableScissor(); }
    public static void fill(GuiGraphicsExtractor g, int left, int top, int right, int bottom, int color) {
        g.fill(left, top, right, bottom, color);
    }
    public static void gradient(GuiGraphicsExtractor g, int left, int top, int right, int bottom, int start, int end) {
        g.fillGradient(left, top, right, bottom, start, end);
    }
    public static void push(GuiGraphicsExtractor g) { g.pose().pushMatrix(); }
    public static void pop(GuiGraphicsExtractor g) { g.pose().popMatrix(); }
    public static void translate(GuiGraphicsExtractor g, float x, float y) { g.pose().translate(x, y); }
    public static void scale(GuiGraphicsExtractor g, float x, float y) { g.pose().scale(x, y); }
    public static void nextLayer(GuiGraphicsExtractor g) { g.nextStratum(); }

    public static void blit(GuiGraphicsExtractor g, Identifier texture, int x, int y, int u, int v, int width, int height) {
        blit(g, texture, x, y, u, v, width, height, 256, 256);
    }
    public static void blit(GuiGraphicsExtractor g, Identifier texture, int x, int y, int u, int v,
        int width, int height, int texWidth, int texHeight) {
        blit(g, texture, x, y, u, v, width, height, width, height, texWidth, texHeight);
    }
    public static void blit(GuiGraphicsExtractor g, Identifier texture, int x, int y, int u, int v,
        int width, int height, int sourceWidth, int sourceHeight, int texWidth, int texHeight) {
        if (g == null || texture == null || width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) return;
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, (float) u, (float) v,
            width, height, sourceWidth, sourceHeight, texWidth, texHeight);
    }
}
