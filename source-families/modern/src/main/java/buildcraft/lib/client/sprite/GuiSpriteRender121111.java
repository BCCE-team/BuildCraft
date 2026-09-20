//? source if >=1.21.11
/* Copyright (c) 2026 the BuildCraft team. MPL-2.0. */
package buildcraft.lib.client.sprite;

import javax.annotation.Nullable;
import org.joml.Matrix3x2f;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.internal.core.render.ISprite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/** Deferred textured GUI quads, with a snapshot of UVs, tint, transform and clipping. */
public final class GuiSpriteRender121111 {
    private GuiSpriteRender121111() { }

    public static int opaqueRgb(int colour) {
        return (colour & 0xFF000000) == 0 ? colour | 0xFF000000 : colour;
    }

    public static void draw(GuiGraphics graphics, ISprite sprite,
        double x0, double y0, double x1, double y1,
        double u0, double v0, double u1, double v1, int argb) {
        if (x1 <= x0 || y1 <= y0 || (argb >>> 24) == 0) return;
        // Binding is used only to resolve the legacy ISprite texture identifier. Neither shader tint
        // nor a global GL scissor is used for drawing. All rendering state is captured below.
        sprite.bindTexture();
        Identifier id = RenderCompat.getShaderTexture();
        if (id == null) id = TextureAtlas.LOCATION_BLOCKS;
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(id);
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        ScreenRectangle scissor = graphics.peekScissorStack();
        int left = (int) Math.floor(x0), top = (int) Math.floor(y0);
        ScreenRectangle bounds = new ScreenRectangle(left, top,
            (int) Math.ceil(x1) - left, (int) Math.ceil(y1) - top).transformMaxBounds(pose);
        if (scissor != null) bounds = scissor.intersection(bounds);
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) return;
        graphics.submitGuiElementRenderState(new Quad(pose, scissor, bounds,
            TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler()),
            (float) x0, (float) y0, (float) x1, (float) y1,
            sprite.getInterpU(u0), sprite.getInterpV(v0),
            sprite.getInterpU(u1), sprite.getInterpV(v1), argb));
    }

    private record Quad(Matrix3x2f pose, @Nullable ScreenRectangle scissorArea, ScreenRectangle bounds,
        TextureSetup textureSetup, float x0, float y0, float x1, float y1,
        float u0, float v0, float u1, float v1, int argb) implements GuiElementRenderState {
        @Override public RenderPipeline pipeline() { return RenderPipelines.GUI_TEXTURED; }
        @Override public void buildVertices(VertexConsumer consumer) {
            consumer.addVertexWith2DPose(pose, x0, y0).setUv(u0, v0).setColor(argb);
            consumer.addVertexWith2DPose(pose, x0, y1).setUv(u0, v1).setColor(argb);
            consumer.addVertexWith2DPose(pose, x1, y1).setUv(u1, v1).setColor(argb);
            consumer.addVertexWith2DPose(pose, x1, y0).setUv(u1, v0).setColor(argb);
        }
    }
}
