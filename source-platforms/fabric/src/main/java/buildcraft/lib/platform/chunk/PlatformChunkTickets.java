package buildcraft.lib.platform.chunk;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Fabric 1.20.1 fallback uses vanilla persisted forced chunks; owner bookkeeping remains in BCChunkTickets. */
public final class PlatformChunkTickets {
    private PlatformChunkTickets() { }
    public static void init() { }
    public static boolean forceChunk(ServerLevel level, BlockPos owner, ChunkPos chunk, boolean add, boolean ticking) {
        level.setChunkForced(chunk.x, chunk.z, add);
        return true;
    }
}
