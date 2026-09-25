package buildcraft.lib.platform.events;

import buildcraft.lib.net.BCNetworkSide;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric event bridge for the server-side normalized BuildCraft events used before client rendering is ported. */
public final class PlatformEvents {
    private PlatformEvents() { }

    public static boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    public static void serverTick(BCEvents.Phase selected, Consumer<BCEvents.ServerTick> handler) {
        if (selected == BCEvents.Phase.START) {
            ServerTickEvents.START_SERVER_TICK.register(server -> handler.accept(new BCEvents.ServerTick(selected)));
        } else {
            ServerTickEvents.END_SERVER_TICK.register(server -> handler.accept(new BCEvents.ServerTick(selected)));
        }
    }

    public static void levelTick(BCEvents.Phase selected, Consumer<BCEvents.LevelTick> handler) {
        if (selected == BCEvents.Phase.START) {
            ServerTickEvents.START_WORLD_TICK.register(level -> handler.accept(new BCEvents.LevelTick(level, selected, BCNetworkSide.SERVER)));
        } else {
            ServerTickEvents.END_WORLD_TICK.register(level -> handler.accept(new BCEvents.LevelTick(level, selected, BCNetworkSide.SERVER)));
        }
    }

    public static void playerTick(BCEvents.Phase selected, Consumer<BCEvents.PlayerTick> handler) {
        Consumer<net.minecraft.server.MinecraftServer> dispatch = server ->
            server.getPlayerList().getPlayers().forEach(player -> handler.accept(new BCEvents.PlayerTick(player, selected)));
        if (selected == BCEvents.Phase.START) ServerTickEvents.START_SERVER_TICK.register(dispatch::accept);
        else ServerTickEvents.END_SERVER_TICK.register(dispatch::accept);
    }

    public static void entityJoin(Consumer<BCEvents.EntityJoin> handler) {
        // No generic postable entity-join callback is required by the legacy server foundation today.
        // Keep the boundary explicit instead of leaking a Fabric callback into common gameplay.
    }

    public static void levelUnload(Consumer<BCEvents.LevelUnload> handler) {
        ServerWorldEvents.UNLOAD.register((server, level) -> handler.accept(new BCEvents.LevelUnload(level)));
    }

    public static void chunkUnload(Consumer<BCEvents.ChunkUnload> handler) {
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> handler.accept(new BCEvents.ChunkUnload(level, chunk.getPos())));
    }

    public static void chunkWatch(Consumer<BCEvents.ChunkWatch> handler) {
        // Fabric has no equivalent global watch callback in this API epoch; callers that need it are wired in Stage 5.
    }
}
