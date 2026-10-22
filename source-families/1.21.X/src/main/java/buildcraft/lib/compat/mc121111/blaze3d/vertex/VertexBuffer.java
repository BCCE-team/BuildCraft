//? source if >=1.21.11
package buildcraft.lib.compat.mc121111.blaze3d.vertex;

import com.mojang.blaze3d.vertex.MeshData;

/** No-op compatibility facade for the direct VBO API removed in 1.21.11. */
public class VertexBuffer implements AutoCloseable {
    public enum Usage { STATIC, DYNAMIC }

    public VertexBuffer(Usage usage) {}
    public void bind() {}
    public void upload(MeshData meshData) {}
    public void drawWithShader(org.joml.Matrix4f modelView, org.joml.Matrix4f projection, Object shader) {}
    public static void unbind() {}
    public void close() {}
}
