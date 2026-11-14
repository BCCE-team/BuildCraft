/* Copyright (c) 2026 the BuildCraft team. MPL-2.0. */
package buildcraft.lib.client.sprite;

import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.internal.core.render.ISprite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/** GUI sprite drawing via the 26.3 render-state extractor, with ARGB colour and atlas UVs. */
public final class GuiSpriteRender2612 {
    private GuiSpriteRender2612() {}

    public static int opaqueRgb(int colour) {
        return (colour & 0xFF000000) == 0 ? colour | 0xFF000000 : colour;
    }

    public static void draw(GuiGraphicsExtractor graphics, ISprite sprite,
            double x0, double y0, double x1, double y1,
            double u0, double v0, double u1, double v1, int argb) {
        if (x1 <= x0 || y1 <= y0 || (argb >>> 24) == 0) return;
        sprite.bindTexture();
        Identifier id = RenderCompat.getShaderTexture();
        if (id == null) id = TextureAtlas.LOCATION_BLOCKS;
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(id);
        int textureWidth = texture.getTextureView().getWidth(0);
        int textureHeight = texture.getTextureView().getHeight(0);
        if (textureWidth <= 0 || textureHeight <= 0) return;

        float startU = sprite.getInterpU(u0);
        float startV = sprite.getInterpV(v0);
        float endU = sprite.getInterpU(u1);
        float endV = sprite.getInterpV(v1);
        int outputWidth = (int) Math.ceil(x1 - x0);
        int outputHeight = (int) Math.ceil(y1 - y0);
        int sourceWidth = Math.max(1, Math.round((endU - startU) * textureWidth));
        int sourceHeight = Math.max(1, Math.round((endV - startV) * textureHeight));

        graphics.blit(RenderPipelines.GUI_TEXTURED, id,
            (int) Math.floor(x0), (int) Math.floor(y0),
            startU * textureWidth, startV * textureHeight,
            outputWidth, outputHeight, sourceWidth, sourceHeight,
            textureWidth, textureHeight, argb);
    }
}
