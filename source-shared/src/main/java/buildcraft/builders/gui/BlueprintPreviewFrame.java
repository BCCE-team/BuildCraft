/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.gui;

/** A small, loader-neutral frame shared by blueprint tooltips and library previews. */
public final class BlueprintPreviewFrame {
    private static final int BACKGROUND = 0xF0100010;
    private static final int TOP_BORDER = 0xFF8271B3;
    private static final int SIDE_BORDER = 0xFF66558F;
    private static final int BOTTOM_BORDER = 0xFF42365D;

    private BlueprintPreviewFrame() { }

    /**
     * Position a hover preview near the cursor without inheriting the vanilla
     * text tooltip's X coordinate. Long tooltips can be repositioned all the
     * way to the left edge; the 3D preview must not follow that relocation.
     *
     * Sliding against the screen boundary (rather than flipping left) also
     * avoids a large one-frame jump when the mouse crosses the boundary.
     * Returns -1 when the viewport cannot fit at the current GUI scale.
     */
    public static int tooltipX(int cursorX, int screenWidth, int viewportWidth) {
        final int margin = 4;
        final int offset = 12;
        if (viewportWidth <= 0 || screenWidth < viewportWidth + 2 * margin) return -1;
        int maxX = screenWidth - viewportWidth - margin;
        return Math.max(margin, Math.min(cursorX + offset, maxX));
    }

    /**
     * Convert the real window-space cursor to scaled GUI coordinates before
     * choosing the preview position. RenderTooltipEvent.Pre.getX() is the
     * tooltip origin and can be changed by wrapping/other tooltip positioners;
     * it is not guaranteed to be the cursor location.
     */
    public static int tooltipXFromWindowMouse(double windowMouseX, int windowWidth,
                                             int guiWidth, int viewportWidth) {
        if (!Double.isFinite(windowMouseX) || windowWidth <= 0 || guiWidth <= 0) return -1;
        int cursorGuiX = (int) Math.round(windowMouseX * guiWidth / windowWidth);
        return tooltipX(cursorGuiX, guiWidth, viewportWidth);
    }

    @FunctionalInterface
    public interface Fill {
        void fill(int left, int top, int right, int bottom, int color);
    }

    /** Draw a dark backing and a 1-pixel outline outside the 3D viewport. */
    public static void draw(Fill target, int x, int y, int width, int height) {
        if (target == null || width <= 0 || height <= 0) return;
        target.fill(x - 4, y - 4, x + width + 4, y + height + 4, BACKGROUND);
        drawOutline(target, x, y, width, height);
    }

    /** Add an explicit border to an already-rendered vanilla tooltip background. */
    public static void drawOutline(Fill target, int x, int y, int width, int height) {
        if (target == null || width <= 0 || height <= 0) return;
        int left = x - 3;
        int top = y - 3;
        int right = x + width + 3;
        int bottom = y + height + 3;
        target.fill(left, top, right, top + 1, TOP_BORDER);
        target.fill(left, top + 1, left + 1, bottom - 1, SIDE_BORDER);
        target.fill(right - 1, top + 1, right, bottom - 1, SIDE_BORDER);
        target.fill(left, bottom - 1, right, bottom, BOTTOM_BORDER);
    }
}
