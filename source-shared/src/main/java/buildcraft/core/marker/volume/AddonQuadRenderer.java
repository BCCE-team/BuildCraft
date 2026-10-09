/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.core.marker.volume;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.phys.AABB;

/** Complete BLOCK-format quads shared by default volume add-ons and Filler Planner ghosts.
 * BLOCK requires position, color, atlas UV, overlay, lightmap and normal for EVERY vertex.
 * Omitting normals throws "Not filled all elements of the vertex" on Forge 1.19.2.
 */
public final class AddonQuadRenderer {
    private static final int FULL_BRIGHT = 0x00F000F0;

    private AddonQuadRenderer() {}

    public static void box(VertexConsumer out, AABB bb, TextureAtlasSprite sprite, int alpha) {
        if (bb == null || sprite == null || alpha <= 0) return;
        // North (-Z)
        quad(out, sprite, 204, alpha, 0, 0, -1,
            bb.minX, bb.maxY, bb.minZ, bb.maxX, bb.maxY, bb.minZ,
            bb.maxX, bb.minY, bb.minZ, bb.minX, bb.minY, bb.minZ);
        // South (+Z)
        quad(out, sprite, 204, alpha, 0, 0, 1,
            bb.minX, bb.minY, bb.maxZ, bb.maxX, bb.minY, bb.maxZ,
            bb.maxX, bb.maxY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ);
        // Down (-Y)
        quad(out, sprite, 127, alpha, 0, -1, 0,
            bb.minX, bb.minY, bb.minZ, bb.maxX, bb.minY, bb.minZ,
            bb.maxX, bb.minY, bb.maxZ, bb.minX, bb.minY, bb.maxZ);
        // Up (+Y)
        quad(out, sprite, 255, alpha, 0, 1, 0,
            bb.minX, bb.maxY, bb.maxZ, bb.maxX, bb.maxY, bb.maxZ,
            bb.maxX, bb.maxY, bb.minZ, bb.minX, bb.maxY, bb.minZ);
        // West (-X); no duplicate vertex (the previous renderer had a degenerate quad here).
        quad(out, sprite, 153, alpha, -1, 0, 0,
            bb.minX, bb.minY, bb.maxZ, bb.minX, bb.maxY, bb.maxZ,
            bb.minX, bb.maxY, bb.minZ, bb.minX, bb.minY, bb.minZ);
        // East (+X)
        quad(out, sprite, 153, alpha, 1, 0, 0,
            bb.maxX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.minZ,
            bb.maxX, bb.maxY, bb.maxZ, bb.maxX, bb.minY, bb.maxZ);
    }

    private static void quad(VertexConsumer out, TextureAtlasSprite sprite, int shade, int alpha,
        float nx, float ny, float nz,
        double x0, double y0, double z0, double x1, double y1, double z1,
        double x2, double y2, double z2, double x3, double y3, double z3) {
        vertex(out, x0, y0, z0, shade, alpha, sprite.getU0(), sprite.getV0(), nx, ny, nz);
        vertex(out, x1, y1, z1, shade, alpha, sprite.getU0(), sprite.getV1(), nx, ny, nz);
        vertex(out, x2, y2, z2, shade, alpha, sprite.getU1(), sprite.getV1(), nx, ny, nz);
        vertex(out, x3, y3, z3, shade, alpha, sprite.getU1(), sprite.getV0(), nx, ny, nz);
    }

    private static void vertex(VertexConsumer out, double x, double y, double z,
        int shade, int alpha, float u, float v, float nx, float ny, float nz) {
        //? if <1.21 {
        out.vertex(x, y, z).color(shade, shade, shade, alpha).uv(u, v)
            .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(FULL_BRIGHT).normal(nx, ny, nz).endVertex();
        //?} else {
        /*?
        out.addVertex((float) x, (float) y, (float) z).setColor(shade, shade, shade, alpha)
            .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(nx, ny, nz);
        ?*/
        //?}
    }
}
