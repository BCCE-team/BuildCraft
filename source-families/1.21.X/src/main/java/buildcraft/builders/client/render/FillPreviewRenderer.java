//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * Copyright (c) 2026 the BuildCraftUnofficial contributors
 * Adapted for BuildCraft Community Edition
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.client.render;

import java.util.List;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;

/**
 * The translucent cubes a Filler's preview draws over every cell its pattern has yet to fill: one slightly shrunken,
 * shaded cube per cell. The cell list comes from {@code FillPreviewCache}; the caller submits the geometry on
 * {@code BCLibRenderTypes.debugFilled()} (untextured, vertex-coloured translucent quads — the 1.12.2 build sampled a
 * plain white sprite for the same look), with a pose stack at the Filler block.
 */
final class FillPreviewRenderer {
    private FillPreviewRenderer() {}

    /** Gap between a cube's faces and its cell's faces, so neighbouring cubes read as separate. */
    private static final double INSET = 0.1;

    /** Emits every cube; {@code origin} is the block the pose stack is positioned at. */
    static void emit(VertexConsumer vb, Matrix4f pose, List<BlockPos> cells, BlockPos origin) {
        for (BlockPos p : cells) {
            double minX = p.getX() - origin.getX() + INSET, maxX = p.getX() - origin.getX() + 1 - INSET;
            double minY = p.getY() - origin.getY() + INSET, maxY = p.getY() - origin.getY() + 1 - INSET;
            double minZ = p.getZ() - origin.getZ() + INSET, maxZ = p.getZ() - origin.getZ() + 1 - INSET;

            // North face (-Z), normal (0, 0, -1)
            vertex(vb, pose, minX, maxY, minZ, 204, 204, 204, 127, 0, 0, 0, 0, -1);
            vertex(vb, pose, maxX, maxY, minZ, 204, 204, 204, 127, 0, 1, 0, 0, -1);
            vertex(vb, pose, maxX, minY, minZ, 204, 204, 204, 127, 1, 1, 0, 0, -1);
            vertex(vb, pose, minX, minY, minZ, 204, 204, 204, 127, 1, 0, 0, 0, -1);

            // South face (+Z), normal (0, 0, 1)
            vertex(vb, pose, minX, minY, maxZ, 204, 204, 204, 127, 0, 0, 0, 0, 1);
            vertex(vb, pose, maxX, minY, maxZ, 204, 204, 204, 127, 0, 1, 0, 0, 1);
            vertex(vb, pose, maxX, maxY, maxZ, 204, 204, 204, 127, 1, 1, 0, 0, 1);
            vertex(vb, pose, minX, maxY, maxZ, 204, 204, 204, 127, 1, 0, 0, 0, 1);

            // Bottom face (-Y), normal (0, -1, 0)
            vertex(vb, pose, minX, minY, minZ, 127, 127, 127, 127, 0, 0, 0, -1, 0);
            vertex(vb, pose, maxX, minY, minZ, 127, 127, 127, 127, 0, 1, 0, -1, 0);
            vertex(vb, pose, maxX, minY, maxZ, 127, 127, 127, 127, 1, 1, 0, -1, 0);
            vertex(vb, pose, minX, minY, maxZ, 127, 127, 127, 127, 1, 0, 0, -1, 0);

            // Top face (+Y), normal (0, 1, 0)
            vertex(vb, pose, minX, maxY, maxZ, 255, 255, 255, 127, 0, 0, 0, 1, 0);
            vertex(vb, pose, maxX, maxY, maxZ, 255, 255, 255, 127, 0, 1, 0, 1, 0);
            vertex(vb, pose, maxX, maxY, minZ, 255, 255, 255, 127, 1, 1, 0, 1, 0);
            vertex(vb, pose, minX, maxY, minZ, 255, 255, 255, 127, 1, 0, 0, 1, 0);

            // West face (-X), normal (-1, 0, 0)
            vertex(vb, pose, minX, minY, maxZ, 153, 153, 153, 127, 0, 0, -1, 0, 0);
            vertex(vb, pose, minX, maxY, maxZ, 153, 153, 153, 127, 0, 1, -1, 0, 0);
            vertex(vb, pose, minX, maxY, minZ, 153, 153, 153, 127, 1, 1, -1, 0, 0);
            vertex(vb, pose, minX, minY, minZ, 153, 153, 153, 127, 1, 0, -1, 0, 0);

            // East face (+X), normal (1, 0, 0)
            vertex(vb, pose, maxX, minY, minZ, 153, 153, 153, 127, 0, 0, 1, 0, 0);
            vertex(vb, pose, maxX, maxY, minZ, 153, 153, 153, 127, 0, 1, 1, 0, 0);
            vertex(vb, pose, maxX, maxY, maxZ, 153, 153, 153, 127, 1, 1, 1, 0, 0);
            vertex(vb, pose, maxX, minY, maxZ, 153, 153, 153, 127, 1, 0, 1, 0, 0);
        }
    }

    private static void vertex(VertexConsumer vb, Matrix4f pose, double x, double y, double z,
                               int r, int g, int b, int a, float u, float v,
                               float nx, float ny, float nz) {
        vb.addVertex(pose, (float) x, (float) y, (float) z)
            .setColor(r, g, b, a)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(0xF000F0)
            .setNormal(nx, ny, nz);
    }
}
