/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render.laser;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import buildcraft.lib.compat.mc2612.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.misc.RenderUtil;
import buildcraft.lib.misc.RenderUtil.AutoTessellator;
import net.minecraft.client.renderer.GameRenderer;
import buildcraft.lib.compat.mc2612.client.renderer.ShaderInstance;
import net.minecraft.world.inventory.InventoryMenu;
import buildcraft.lib.compat.RenderCompat;
public abstract class LaserCompiledList {
    public abstract void render(PoseStack pose, Matrix4f matrix);

    public abstract void delete();

    public static class Builder implements ILaserRenderer, AutoCloseable {
    	private final AutoTessellator tess;
        private final BufferBuilder bufferBuilder;
        private final boolean useNormalColour;

        public Builder(boolean useNormalColour) {
            this.useNormalColour = useNormalColour;
            tess = RenderUtil.getThreadLocalUnusedTessellator();
            bufferBuilder = tess.tessellator.begin(
                VertexFormat.Mode.QUADS,
                useNormalColour ? DefaultVertexFormat.BLOCK : DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP
            );
        }

        public void vertex(float x, float y, float z, float u, float v, int lmap, float nx, float ny, float nz,
            float diffuse) {
            bufferBuilder.addVertex(x, y, z);
            if (useNormalColour) 
                bufferBuilder.setColor(diffuse, diffuse, diffuse, 1.0f);
            else
            	bufferBuilder.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            bufferBuilder.setUv(u, v);
            bufferBuilder.setUv2((lmap >> 16) & 0xFFFF, lmap & 0xFFFF);
            if(useNormalColour)
            	bufferBuilder.setNormal(nx, ny, nz);
        }

        public LaserCompiledList build() {
            VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        	vertexBuffer.bind();
        	vertexBuffer.upload(bufferBuilder.buildOrThrow());
        	VertexBuffer.unbind();
        	return new Vbo(useNormalColour, vertexBuffer);
        }

        public void close() {
            tess.close();
        }
    }

    private static class Vbo extends LaserCompiledList {
        private final boolean useNormalColour;
        private final VertexBuffer vertexBuffer;

        private Vbo(boolean useColour, VertexBuffer vertexBuffer) {
            this.useNormalColour = useColour;
            this.vertexBuffer = vertexBuffer;
        }

        public void render(PoseStack pose, Matrix4f matrix) {
            LaserRenderer_BC8.setupLaserRenderState();
            vertexBuffer.bind();
            RenderCompat.setShader(null);
            RenderCompat.setShaderTexture(0, net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            
            ShaderInstance shaderinstance = null;
            vertexBuffer.drawWithShader(pose.last().pose(), matrix, shaderinstance);
            
            VertexBuffer.unbind();

        }

        public void delete() {
        	vertexBuffer.close();
        }
    }
}
