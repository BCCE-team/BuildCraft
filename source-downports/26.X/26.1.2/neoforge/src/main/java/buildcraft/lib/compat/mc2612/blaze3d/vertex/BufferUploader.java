package buildcraft.lib.compat.mc2612.blaze3d.vertex;

import com.mojang.blaze3d.vertex.MeshData;

/** Compatibility facade for immediate-mode BuildCraft GUI rendering. */
public final class BufferUploader {
    private BufferUploader() {}

    public static void drawWithShader(MeshData meshData) {
        // The current renderer has no immediate uploader on this path. Compatibility
        // call sites are no-ops; active renderers submit geometry through queues/live buffers.
    }
}
