//? source if >=1.21.11
package buildcraft.lib.compat.mc121111.blaze3d.vertex;

import com.mojang.blaze3d.vertex.MeshData;

/** Compatibility facade for immediate-mode BuildCraft GUI rendering. */
public final class BufferUploader {
    private BufferUploader() {}

    public static void drawWithShader(MeshData meshData) {
        // The 1.21.11 renderer has no immediate uploader on this path. Compatibility
        // call sites are no-ops; active renderers submit geometry through queues/live buffers.
    }
}
