/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.sprite;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.gui.pos.IGuiArea;

/** Defines and draws a 9-sliced sprite. */
public class SpriteNineSliced {
    public final ISprite sprite;
    public final double xMin, yMin, xMax, yMax;
    public final double xScale, yScale;
    
    private double transX, transY;

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

    public void draw(GuiGraphics guiGraphics, IGuiArea element) {
        draw(guiGraphics.pose(), element.getX(), element.getY(), element.getWidth(), element.getHeight());
    }

    public void draw(GuiGraphics guiGraphics, double x, double y, double width, double height) {
        draw(guiGraphics.pose(), x, y, width, height);
    }

    public void draw(PoseStack pose, IGuiArea element) {
        draw(pose, element.getX(), element.getY(), element.getWidth(), element.getHeight());
    }

    public void draw(PoseStack pose, double x, double y, double width, double height) {
    	Matrix4f matrix4f = pose.last().pose();
        float[] shaderColour = RenderSystem.getShaderColor();
        float red = shaderColour.length > 0 ? shaderColour[0] : 1.0F;
        float green = shaderColour.length > 1 ? shaderColour[1] : 1.0F;
        float blue = shaderColour.length > 2 ? shaderColour[2] : 1.0F;
        float alpha = shaderColour.length > 3 ? shaderColour[3] : 1.0F;
    	RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        sprite.bindTexture();
        BufferBuilder vb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        transX = x;
        transY = y;

        double sx = xScale;
        double sy = yScale;
        double[] xa = { 0, xMin * sx, width + (xMax - 1) * sx, width };
        double[] ya = { 0, yMin * sy, height + (yMax - 1) * sy, height };

        double[] ua = { 0, xMin, xMax, 1 };
        double[] va = { 0, yMin, yMax, 1 };

        quad(matrix4f, vb, xa, ya, ua, va, 0, 0, red, green, blue, alpha);
        quad(matrix4f, vb, xa, ya, ua, va, 0, 1, red, green, blue, alpha);
        quad(matrix4f, vb, xa, ya, ua, va, 0, 2, red, green, blue, alpha);

        quad(matrix4f, vb, xa, ya, ua, va, 1, 0, red, green, blue, alpha);
        quad(matrix4f, vb, xa, ya, ua, va, 1, 1, red, green, blue, alpha);
        quad(matrix4f, vb, xa, ya, ua, va, 1, 2, red, green, blue, alpha);

        quad(matrix4f, vb, xa, ya, ua, va, 2, 0, red, green, blue, alpha);
        quad(matrix4f, vb, xa, ya, ua, va, 2, 1, red, green, blue, alpha);
        quad(matrix4f, vb, xa, ya, ua, va, 2, 2, red, green, blue, alpha);

        BufferUploader.drawWithShader(vb.buildOrThrow());
        transX = 0;
        transY = 0;
    }

    private void quad(Matrix4f matrix4f, BufferBuilder vb, double[] x, double[] y, double[] u, double[] v, int xIndex,
        int yIndex, float red, float green, float blue, float alpha) {
        int xis = xIndex;
        int xIB = xIndex + 1;

        int yis = yIndex;
        int yIB = yIndex + 1;

        vertex(matrix4f, vb, x[xis], y[yis], u[xis], v[yis], red, green, blue, alpha);
        vertex(matrix4f, vb, x[xis], y[yIB], u[xis], v[yIB], red, green, blue, alpha);
        vertex(matrix4f, vb, x[xIB], y[yIB], u[xIB], v[yIB], red, green, blue, alpha);
        vertex(matrix4f, vb, x[xIB], y[yis], u[xIB], v[yis], red, green, blue, alpha);
    }

    private void vertex(Matrix4f matrix4f, BufferBuilder vb, double x, double y, double texU, double texV,
        float red, float green, float blue, float alpha) {
        vb.addVertex(matrix4f, (float) (x + transX), (float) (y + transY), 0)
            .setUv(sprite.getInterpU(texU), sprite.getInterpV(texV))
            .setColor(red, green, blue, alpha);
    }
}
