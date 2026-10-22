package buildcraft.lib.chunkload;
import java.util.Set;
import buildcraft.lib.platform.chunk.BCChunkTickets;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Stable internal facade used by machines; ownership and lifecycle live in BCChunkTickets. */
public final class ChunkLoaderManager {
    private ChunkLoaderManager() {}
    public static void init() { BCChunkTickets.init(); }
    public static <T extends BlockEntity & IChunkLoadingTile> void loadChunksForTile(T tile) { BCChunkTickets.loadChunksForTile(tile); }
    public static <T extends BlockEntity & IChunkLoadingTile> void releaseChunksFor(T tile) { BCChunkTickets.releaseChunksFor(tile); }
    public static <T extends BlockEntity & IChunkLoadingTile> Set<ChunkPos> getChunksToLoad(T tile) { return BCChunkTickets.getChunksToLoad(tile); }
}
