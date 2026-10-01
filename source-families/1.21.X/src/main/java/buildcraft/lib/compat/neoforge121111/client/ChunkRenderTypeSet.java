//? source if >=1.21.11
package buildcraft.lib.compat.neoforge121111.client;

import java.util.Collection;
import java.util.List;

import net.minecraft.client.renderer.rendertype.RenderType;

/** Compatibility facade for NeoForge ChunkRenderTypeSet semantics on 1.21.11. */
public final class ChunkRenderTypeSet {
    private final Collection<RenderType> types;

    private ChunkRenderTypeSet(Collection<RenderType> types) {
        this.types = types;
    }

    public static ChunkRenderTypeSet of(Collection<RenderType> types) {
        return new ChunkRenderTypeSet(types);
    }

    public static ChunkRenderTypeSet of(RenderType... types) {
        return new ChunkRenderTypeSet(List.of(types));
    }

    public boolean contains(RenderType type) {
        return types.contains(type);
    }
}
