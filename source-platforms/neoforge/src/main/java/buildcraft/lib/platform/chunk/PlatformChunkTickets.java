package buildcraft.lib.platform.chunk;

import java.util.Set;
import buildcraft.lib.BCLib;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

/** NeoForge ticket binding. Persisted controller ID is deliberately unchanged. */
public final class PlatformChunkTickets {
    private static final TicketController CONTROLLER = new TicketController(
        ResourceLocation.fromNamespaceAndPath(BCLib.MODID, "machines"), PlatformChunkTickets::validate);
    private PlatformChunkTickets() {}
    public static void init() { /* NeoForge registers on its mod event, not the common setup callback. */ }
    public static void registerTicketController(RegisterTicketControllersEvent event) { event.register(CONTROLLER); }
    private static void validate(ServerLevel level, TicketHelper helper) {
        BCChunkTickets.validateTickets(level, new BCTicketOwners() {
            public Set<BlockPos> owners() { return helper.getBlockTickets().keySet(); }
            public void removeAllTickets(BlockPos owner) { helper.removeAllTickets(owner); }
        });
    }
    public static boolean forceChunk(ServerLevel level, BlockPos owner, ChunkPos chunk, boolean add, boolean ticking) {
        return CONTROLLER.forceChunk(level, owner, chunk.x, chunk.z, add, ticking);
    }
}
