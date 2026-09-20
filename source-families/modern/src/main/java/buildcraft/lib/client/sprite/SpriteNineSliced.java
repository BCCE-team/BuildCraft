//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.sprite;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.gui.pos.IGuiArea;
import net.minecraft.client.gui.GuiGraphics;

/** Defines and draws a 9-sliced sprite. */
public class SpriteNineSliced {
    public final ISprite sprite;
    public final double xMin, yMin, xMax, yMax;
    public final double xScale, yScale;

    public SpriteNineSliced(ISprite sprite, int xMin, int yMin, int xMax, int yMax, int textureSize) {
        this(sprite, xMin, yMin, xMax, yMax, textureSize, textureSize);
    }

    public SpriteNineSliced(ISprite sprite, int xMin, int yMin, int xMax, int yMax, int xScale, int yScale) {
        this.sprite = sprite;
        this.xMin = xMin / (double) xScale;
        this.yMin = yMin / (double) yScale;
        this.xMax = xMax / (double) xScale;
        this.yMax = yMax / (double) yScale;
        this.xScale = xScale;
        this.yScale = yScale;
    }

    public SpriteNineSliced(ISprite sprite, double xMin, double yMin, double xMax, double yMax, double scale) {
        this(sprite, xMin, yMin, xMax, yMax, scale, scale);
    }

    public SpriteNineSliced(ISprite sprite, double xMin, double yMin, double xMax, double yMax, double xScale,
        double yScale) {
        this.sprite = sprite;
        this.xMin = xMin;
        this.yMin = yMin;
        this.xMax = xMax;
        this.yMax = yMax;
        this.xScale = xScale;
        this.yScale = yScale;
    }

    public void draw(GuiGraphics graphics, IGuiArea area) {
        draw(graphics, area, 0xFFFFFFFF);
    }

    public void draw(GuiGraphics graphics, IGuiArea area, int argb) {
        draw(graphics, area.getX(), area.getY(), area.getWidth(), area.getHeight(), argb);
    }

    public void draw(GuiGraphics graphics, double x, double y, double width, double height) {
        draw(graphics, x, y, width, height, 0xFFFFFFFF);
    }

    /** Explicit ARGB, including intentional alpha=0. Legacy RGB callers must add opaque alpha. */
    public void draw(GuiGraphics graphics, double x, double y, double width, double height, int argb) {
        if (width <= 0 || height <= 0) return;
        double left = Math.min(width, xMin * xScale);
        double right = Math.min(width - left, (1 - xMax) * xScale);
        double top = Math.min(height, yMin * yScale);
        double bottom = Math.min(height - top, (1 - yMax) * yScale);
        double[] xs = { x, x + left, x + width - right, x + width };
        double[] ys = { y, y + top, y + height - bottom, y + height };
        double[] us = { 0, xMin, xMax, 1 };
        double[] vs = { 0, yMin, yMax, 1 };
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                GuiSpriteRender121111.draw(graphics, sprite,
                    xs[col], ys[row], xs[col + 1], ys[row + 1],
                    us[col], vs[row], us[col + 1], vs[row + 1], argb);
            }
        }
    }
}
