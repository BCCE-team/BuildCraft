//? source if >=26.2
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.client.render.laser;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import buildcraft.lib.client.render.compat.BCWorldGeometry;
import buildcraft.lib.compat.RenderCompat;

public abstract class LaserCompiledList {
    public abstract void render(PoseStack pose, Matrix4f matrix);
    public abstract void delete();

    public static final class Builder implements ILaserRenderer, AutoCloseable {
        private final LaserCompiledBuffer.Builder vertices;
        private boolean closed;

        public Builder(boolean useNormalColour) {
            vertices = new LaserCompiledBuffer.Builder(useNormalColour);
        }

        @Override
        public void vertex(float x, float y, float z, float u, float v, int light,
            float nx, float ny, float nz, float diffuse) {
            ensureOpen();
            vertices.vertex(x, y, z, u, v, light, nx, ny, nz, diffuse);
        }

        public LaserCompiledList build() {
            ensureOpen();
            closed = true;
            return new Compiled(vertices.build());
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Laser geometry builder is closed");
        }

        @Override
        public void close() { closed = true; }
    }

    private static final class Compiled extends LaserCompiledList {
        private LaserCompiledBuffer vertices;

        private Compiled(LaserCompiledBuffer vertices) { this.vertices = vertices; }

        @Override
        public void render(PoseStack pose, Matrix4f matrix) {
            if (vertices == null) throw new IllegalStateException("Laser geometry was deleted");
            vertices.render(pose.last().pose(), pose.last().normal(), BCWorldGeometry.buffer(RenderCompat.cutout()));
        }

        @Override
        public void delete() { vertices = null; }
    }
}
