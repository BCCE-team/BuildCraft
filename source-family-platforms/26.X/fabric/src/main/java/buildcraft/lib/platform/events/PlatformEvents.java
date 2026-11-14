package buildcraft.lib.platform.events;

import buildcraft.lib.net.BCNetworkSide;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Normalized 26.3 lifecycle events. Server player ticks and chunk-watch
 * notifications are emitted by exact vanilla lifecycle mixins rather than
 * approximated with a once-per-server-tick scan.
 */
public final class PlatformEvents {
    private static final List<Consumer<BCEvents.PlayerTick>> PLAYER_START = new CopyOnWriteArrayList<>();
    private static final List<Consumer<BCEvents.PlayerTick>> PLAYER_END = new CopyOnWriteArrayList<>();
    private static final List<Consumer<BCEvents.ChunkWatch>> CHUNK_WATCH = new CopyOnWriteArrayList<>();

    private PlatformEvents() {}

    public static boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    public static void serverTick(BCEvents.Phase phase, Consumer<BCEvents.ServerTick> handler) {
        if (phase == BCEvents.Phase.START) {
            ServerTickEvents.START_SERVER_TICK.register(server -> handler.accept(new BCEvents.ServerTick(phase)));
        } else {
            ServerTickEvents.END_SERVER_TICK.register(server -> handler.accept(new BCEvents.ServerTick(phase)));
        }
    }

    public static void levelTick(BCEvents.Phase phase, Consumer<BCEvents.LevelTick> handler) {
        if (phase == BCEvents.Phase.START) {
            ServerTickEvents.START_LEVEL_TICK.register(level ->
                handler.accept(new BCEvents.LevelTick(level, phase, BCNetworkSide.SERVER)));
        } else {
            ServerTickEvents.END_LEVEL_TICK.register(level ->
                handler.accept(new BCEvents.LevelTick(level, phase, BCNetworkSide.SERVER)));
        }
        if (isClient()) ClientEvents.levelTick(phase, handler);
    }

    public static void playerTick(BCEvents.Phase phase, Consumer<BCEvents.PlayerTick> handler) {
        (phase == BCEvents.Phase.START ? PLAYER_START : PLAYER_END).add(handler);
        // Client ticks are emitted by LocalPlayerTickMixin at the real entity tick boundary.
    }

    /** Called by ServerPlayerTickMixin on the entity's own tick, not the server clock. */
    public static void fireServerPlayerTick(ServerPlayer player, BCEvents.Phase phase) {
        for (Consumer<BCEvents.PlayerTick> listener : phase == BCEvents.Phase.START ? PLAYER_START : PLAYER_END) {
            listener.accept(new BCEvents.PlayerTick(player, phase));
        }
    }

    /** Actual local-player entity tick, not the aggregate client game-loop tick. */
    public static void fireClientPlayerTick(net.minecraft.world.entity.player.Player player, BCEvents.Phase phase) {
        for (Consumer<BCEvents.PlayerTick> listener : phase == BCEvents.Phase.START ? PLAYER_START : PLAYER_END) {
            listener.accept(new BCEvents.PlayerTick(player, phase));
        }
    }

    public static void chunkWatch(Consumer<BCEvents.ChunkWatch> handler) {
        CHUNK_WATCH.add(handler);
    }

    /** Called after sending the vanilla chunk payload to the watching player. */
    public static void fireChunkWatch(ServerPlayer player, ServerLevel level) {
        for (Consumer<BCEvents.ChunkWatch> listener : CHUNK_WATCH) {
            listener.accept(new BCEvents.ChunkWatch(player, level));
        }
    }

    public static void entityJoin(Consumer<BCEvents.EntityJoin> handler) {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> handler.accept(new BCEvents.EntityJoin(entity)));
        if (isClient()) ClientEvents.entityJoin(handler);
    }

    public static void levelUnload(Consumer<BCEvents.LevelUnload> handler) {
        ServerLevelEvents.UNLOAD.register((server, level) ->
            handler.accept(new BCEvents.LevelUnload(level)));
    }

    public static void chunkUnload(Consumer<BCEvents.ChunkUnload> handler) {
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
            handler.accept(new BCEvents.ChunkUnload(level, chunk.getPos())));
    }

    /** Never resolve these client-only API classes on the dedicated server. */
    private static final class ClientEvents {
        private static void levelTick(BCEvents.Phase phase, Consumer<BCEvents.LevelTick> handler) {
            if (phase == BCEvents.Phase.START) {
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_LEVEL_TICK.register(level ->
                    handler.accept(new BCEvents.LevelTick(level, phase, BCNetworkSide.CLIENT)));
            } else {
                net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_LEVEL_TICK.register(level ->
                    handler.accept(new BCEvents.LevelTick(level, phase, BCNetworkSide.CLIENT)));
            }
        }

        private static void entityJoin(Consumer<BCEvents.EntityJoin> handler) {
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents.ENTITY_LOAD.register(
                (entity, level) -> handler.accept(new BCEvents.EntityJoin(entity)));
        }
    }
}
