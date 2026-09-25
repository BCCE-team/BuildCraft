package buildcraft.lib.net;
// Loader boundary owner: net.fabricmc (implementation may delegate to vanilla/common contracts).

import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Minimal process-local server handle used only by loader adapters that need player enumeration. */
public final class FabricServerState {
    private static volatile MinecraftServer server;
    private FabricServerState() { }
    public static void started(MinecraftServer value) { server = value; }
    public static void stopped(MinecraftServer value) { if (server == value) server = null; }
    public static List<ServerPlayer> players() {
        MinecraftServer current = server;
        return current == null ? List.of() : List.copyOf(current.getPlayerList().getPlayers());
    }
}
