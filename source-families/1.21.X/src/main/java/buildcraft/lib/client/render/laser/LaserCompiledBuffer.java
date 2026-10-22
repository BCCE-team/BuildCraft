/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render.laser;


import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import net.minecraft.client.renderer.texture.OverlayTexture;
public class LaserCompiledBuffer {
    private final int vertices;
    private final float[] data;

    public LaserCompiledBuffer(int vertices, float[] data) {
        this.vertices = vertices;
		this.data = data;
    }

    /** Assumes the buffer uses {@link DefaultVertexFormats#BLOCK} */
    public void render(Matrix4f pose, Matrix3f normal, VertexConsumer buffer) {
    	int counter = 0;
//    	BCLog.logger.debug(""+vertices);
        for (int i = 0; i < vertices; i++) {
            // POSITION_3F
            buffer.addVertex(pose, data[counter++], data[counter++], data[counter++]);

            // COLOR_4UB
            int c = (int)data[counter++];
            buffer.setColor(c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF, (c >> 24) & 0xFF);

            // TEX_2F
            buffer.setUv(data[counter++] + 0.00f, data[counter++] + 0.000f);

            // TEX_2S
            int lmap = (int)data[counter++];
            buffer.setLight(lmap);
            float nx = data[counter++];
            float ny = data[counter++];
            float nz = data[counter++];
            buffer.setNormal(
                normal.m00() * nx + normal.m10() * ny + normal.m20() * nz,
                normal.m01() * nx + normal.m11() * ny + normal.m21() * nz,
                normal.m02() * nx + normal.m12() * ny + normal.m22() * nz
            );
            buffer.setOverlay(OverlayTexture.NO_OVERLAY);
        }
    }

    public static class Builder implements ILaserRenderer {
        private final boolean useNormalColour;
        private final FloatList floatData = new FloatArrayList();//pos + color + uv + normal
        private int vertices = 0;

        public Builder(boolean useNormalColour) {
            this.useNormalColour = useNormalColour;
        }

        @Override
        public void vertex(float x, float y, float z, float u, float v, int lmap, float nx, float ny, float nz, float diffuse) {
            // POSITION_3F
        	floatData.add(x);
        	floatData.add(y);
        	floatData.add(z);

            // COLOR_4UB
            if (useNormalColour) {
                int c = (int) (diffuse * 0xFF);
                floatData.add(c | c << 8 | c << 16 | 0xFF << 24);
            } else {
            	floatData.add(0xFF_FF_FF_FF);
            }

            // TEX_2F
            floatData.add(u);
            floatData.add(v);

            // TEX_2S
            floatData.add(lmap);
            
            // Normal
            floatData.add(nx);
            floatData.add(ny);
            floatData.add(nz);

            vertices++;
        }

        public LaserCompiledBuffer build() {
            return new LaserCompiledBuffer(vertices, floatData.toFloatArray());
        }
    }
}
