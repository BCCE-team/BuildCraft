//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render.tile;

import buildcraft.lib.client.model.MutableVertex;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.InventoryMenu;
import buildcraft.lib.compat.RenderCompat;

/** A variable sized element (like LED) that can render somewhere in a BlockEntityRender. Passing a resolver instance will let you
 * modify the location, colour, lightmap, and size of the single element. This does not allow for different textures. */
public class RenderPartCube {
    /** The centre of this element. */
    public final MutableVertex center = new MutableVertex();
    public float sizeX = 1 / 16.0f, sizeY = 1 / 16.0f, sizeZ = 1 / 16.0f;
    protected float Umin, Umax, Vmin, Vmax;

    /** Constructs a simple cube configured for a LED. */
    public RenderPartCube() {
        this(1 / 16.0, 1 / 16.0, 1 / 16.0);
    }

    public RenderPartCube(double x, double y, double z) {
        center.positiond(x, y, z);
    }

    public void setWhiteTex() {
    	//use quartz to replace White BackGround;
        TextureAtlasSprite sprite = RenderCompat.blockSprites().apply(Identifier.fromNamespaceAndPath("minecraft", "block/quartz_block_bottom"));
        if (sprite == null) {
            center.texf(0.5F, 0.5F);
            Umin = 0.0F;
            Vmin = 0.0F;
            Umax = 1.0F;
            Vmax = 1.0F;
            return;
        }
        // Reset the vertex so that edits don't spill out to other tiles.
        center.texf(sprite.getU(8.0F / 16.0F), sprite.getV(8.0F / 16.0F));
        Umin = sprite.getU0();
        Vmin = sprite.getV0();
        Umax = sprite.getU(4.0F / 16.0F);
        Vmax = sprite.getV(4.0F / 16.0F);
    }

    /** Renders an element, without changing the vertex. However this does ignore the "normal" and "texture" components
     * of the vertex. */
    public void render(Matrix4f pose, Matrix3f normalMatrix, VertexConsumer bb) {
        Vector3f pos = center.positionvf();
        float x = pos.x();
        float y = pos.y();
        float z = pos.z();

        float rX = sizeX / 2;
        float rY = sizeY / 2;
        float rZ = sizeZ / 2;

        vertex(pose, normalMatrix, bb, center, x - rX, y + rY, z + rZ, Umin, Vmax);
        vertex(pose, normalMatrix, bb, center, x + rX, y + rY, z + rZ, Umax, Vmax);
        vertex(pose, normalMatrix, bb, center, x + rX, y + rY, z - rZ, Umax, Vmin);
        vertex(pose, normalMatrix, bb, center, x - rX, y + rY, z - rZ, Umin, Vmin);

        vertex(pose, normalMatrix, bb, center, x - rX, y - rY, z - rZ, Umin, Vmin);
        vertex(pose, normalMatrix, bb, center, x + rX, y - rY, z - rZ, Umax, Vmin);
        vertex(pose, normalMatrix, bb, center, x + rX, y - rY, z + rZ, Umax, Vmax);
        vertex(pose, normalMatrix, bb, center, x - rX, y - rY, z + rZ, Umin, Vmax);

        vertex(pose, normalMatrix, bb, center, x - rX, y - rY, z + rZ, Umin, Vmax);
        vertex(pose, normalMatrix, bb, center, x - rX, y + rY, z + rZ, Umax, Vmax);
        vertex(pose, normalMatrix, bb, center, x - rX, y + rY, z - rZ, Umax, Vmin);
        vertex(pose, normalMatrix, bb, center, x - rX, y - rY, z - rZ, Umin, Vmin);

        vertex(pose, normalMatrix, bb, center, x + rX, y - rY, z - rZ, Umin, Vmin);
        vertex(pose, normalMatrix, bb, center, x + rX, y + rY, z - rZ, Umax, Vmin);
        vertex(pose, normalMatrix, bb, center, x + rX, y + rY, z + rZ, Umax, Vmax);
        vertex(pose, normalMatrix, bb, center, x + rX, y - rY, z + rZ, Umin, Vmax);

        vertex(pose, normalMatrix, bb, center, x - rX, y - rY, z - rZ, Umin, Vmin);
        vertex(pose, normalMatrix, bb, center, x - rX, y + rY, z - rZ, Umin, Vmax);
        vertex(pose, normalMatrix, bb, center, x + rX, y + rY, z - rZ, Umax, Vmax);
        vertex(pose, normalMatrix, bb, center, x + rX, y - rY, z - rZ, Umax, Vmin);

        vertex(pose, normalMatrix, bb, center, x + rX, y - rY, z + rZ, Umax, Vmin);
        vertex(pose, normalMatrix, bb, center, x + rX, y + rY, z + rZ, Umax, Vmax);
        vertex(pose, normalMatrix, bb, center, x - rX, y + rY, z + rZ, Umin, Vmax);
        vertex(pose, normalMatrix, bb, center, x - rX, y - rY, z + rZ, Umin, Vmin);
    }

    private static void vertex(Matrix4f pose, Matrix3f normalMatrix, VertexConsumer vb, MutableVertex center, float x, float y, float z, float u, float v) {
        // Using DefaultVertexFormats.BLOCK
        // -- POSITION_3F // pos
        // -- COLOR_4UB // colour
        // -- TEX_2F // texture
        // -- TEX_2S // lightmap
        vb.addVertex(pose, x, y, z);
        center.renderColour(vb);
        vb.setUv(u, v);
        center.renderOverlay(vb, OverlayTexture.NO_OVERLAY);
//        vb.color(1f, 1f, 1f, 1f);

        center.renderLightMap(vb);

        center.renderNormal(normalMatrix, vb);
    }
}
