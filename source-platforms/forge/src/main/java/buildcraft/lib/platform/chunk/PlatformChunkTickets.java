package buildcraft.lib.platform.chunk;

import java.util.Set;
import buildcraft.lib.BCLib;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.world.ForgeChunkManager;

/** Forge ticket binding. Same mod ID, owner positions and ticking flags as existing worlds. */
public final class PlatformChunkTickets {
    private static boolean initialized;
    private PlatformChunkTickets() {}
    public static synchronized void init() {
        if (initialized) return;
        ForgeChunkManager.setForcedChunkLoadingCallback(BCLib.MODID, PlatformChunkTickets::validate);
        initialized = true;
    }
    private static void validate(ServerLevel level, ForgeChunkManager.TicketHelper helper) {
        BCChunkTickets.validateTickets(level, new BCTicketOwners() {
            public Set<BlockPos> owners() { return helper.getBlockTickets().keySet(); }
            public void removeAllTickets(BlockPos owner) { helper.removeAllTickets(owner); }
        });
    }
    public static boolean forceChunk(ServerLevel level, BlockPos owner, ChunkPos chunk, boolean add, boolean ticking) {
        return ForgeChunkManager.forceChunk(level, BCLib.MODID, owner, chunk.x, chunk.z, add, ticking);
    }
}
