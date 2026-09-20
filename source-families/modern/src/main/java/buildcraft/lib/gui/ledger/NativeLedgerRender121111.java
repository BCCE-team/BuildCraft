//? source if >=1.21.11
package buildcraft.lib.gui.ledger;

import buildcraft.lib.gui.pos.IGuiArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Native deferred-GUI renderer for BuildCraft ledger/tab textures on Minecraft 1.21.11. */
final class NativeLedgerRender121111 {
    private static final Identifier LEDGER_LEFT = texture("ledger_left");
    private static final Identifier LEDGER_RIGHT = texture("ledger_right");
    private static final Identifier HELP_SPLIT = texture("help_split");

    private NativeLedgerRender121111() {
    }

    static void drawLedgerFrame(GuiGraphics graphics, boolean expandPositive, int colour,
        double x, double y, double width, double height) {
        drawNineSlice(
            graphics,
            expandPositive ? LEDGER_RIGHT : LEDGER_LEFT,
            colour,
            x, y, width, height,
            0, 0, 16, 16,
            4, 4, 4, 4,
            16, 16
        );
    }

    static void drawHelpHighlight(GuiGraphics graphics, boolean hovered, boolean selected, int colour, IGuiArea area) {
        int u = hovered ? 8 : 0;
        int v = selected ? 8 : 0;
        drawNineSlice(
            graphics,
            HELP_SPLIT,
            colour,
            area.getX(), area.getY(), area.getWidth(), area.getHeight(),
            u, v, 8, 8,
            2, 2, 2, 2,
            16, 16
        );
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("buildcraftlib", "textures/icons/" + name + ".png");
    }

    private static void drawNineSlice(GuiGraphics graphics, Identifier texture, int colour,
        double x, double y, double width, double height,
        int sourceX, int sourceY, int sourceWidth, int sourceHeight,
        int left, int top, int right, int bottom,
        int textureWidth, int textureHeight) {

        int dx0 = (int) Math.round(x);
        int dy0 = (int) Math.round(y);
        int totalWidth = Math.max(0, (int) Math.round(width));
        int totalHeight = Math.max(0, (int) Math.round(height));
        if (totalWidth <= 0 || totalHeight <= 0) {
            return;
        }

        int drawLeft = Math.min(left, totalWidth);
        int drawRight = Math.min(right, Math.max(0, totalWidth - drawLeft));
        int drawTop = Math.min(top, totalHeight);
        int drawBottom = Math.min(bottom, Math.max(0, totalHeight - drawTop));
        int drawCenterWidth = Math.max(0, totalWidth - drawLeft - drawRight);
        int drawCenterHeight = Math.max(0, totalHeight - drawTop - drawBottom);

        int sourceCenterWidth = Math.max(0, sourceWidth - left - right);
        int sourceCenterHeight = Math.max(0, sourceHeight - top - bottom);

        // Top row.
        blit(graphics, texture, colour, dx0, dy0, drawLeft, drawTop,
            sourceX, sourceY, left, top, textureWidth, textureHeight);
        blit(graphics, texture, colour, dx0 + drawLeft, dy0, drawCenterWidth, drawTop,
            sourceX + left, sourceY, sourceCenterWidth, top, textureWidth, textureHeight);
        blit(graphics, texture, colour, dx0 + drawLeft + drawCenterWidth, dy0, drawRight, drawTop,
            sourceX + sourceWidth - right, sourceY, right, top, textureWidth, textureHeight);

        // Middle row.
        blit(graphics, texture, colour, dx0, dy0 + drawTop, drawLeft, drawCenterHeight,
            sourceX, sourceY + top, left, sourceCenterHeight, textureWidth, textureHeight);
        blit(graphics, texture, colour, dx0 + drawLeft, dy0 + drawTop, drawCenterWidth, drawCenterHeight,
            sourceX + left, sourceY + top, sourceCenterWidth, sourceCenterHeight, textureWidth, textureHeight);
        blit(graphics, texture, colour, dx0 + drawLeft + drawCenterWidth, dy0 + drawTop, drawRight, drawCenterHeight,
            sourceX + sourceWidth - right, sourceY + top, right, sourceCenterHeight, textureWidth, textureHeight);

        // Bottom row.
        blit(graphics, texture, colour, dx0, dy0 + drawTop + drawCenterHeight, drawLeft, drawBottom,
            sourceX, sourceY + sourceHeight - bottom, left, bottom, textureWidth, textureHeight);
        blit(graphics, texture, colour, dx0 + drawLeft, dy0 + drawTop + drawCenterHeight, drawCenterWidth, drawBottom,
            sourceX + left, sourceY + sourceHeight - bottom, sourceCenterWidth, bottom, textureWidth, textureHeight);
        blit(graphics, texture, colour, dx0 + drawLeft + drawCenterWidth, dy0 + drawTop + drawCenterHeight, drawRight, drawBottom,
            sourceX + sourceWidth - right, sourceY + sourceHeight - bottom, right, bottom, textureWidth, textureHeight);
    }

    private static void blit(GuiGraphics graphics, Identifier texture, int colour,
        int x, int y, int width, int height,
        int u, int v, int sourceWidth, int sourceHeight,
        int textureWidth, int textureHeight) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            texture,
            x, y,
            (float) u, (float) v,
            width, height,
            sourceWidth, sourceHeight,
            textureWidth, textureHeight,
            colour
        );
    }
}
