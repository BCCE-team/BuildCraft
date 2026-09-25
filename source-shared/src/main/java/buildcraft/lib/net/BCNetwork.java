package buildcraft.lib.net;

import java.util.Objects;

import buildcraft.lib.platform.runtime.PlatformRuntime;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/** Loader-neutral send facade for already registered BuildCraft messages. */
public final class BCNetwork {
    private BCNetwork() {
    }

    public static void sendToAll(Object message) {
        PlatformRuntime.network().sendToAll(Objects.requireNonNull(message, "message"));
    }

    public static void sendTo(Object message, ServerPlayer player) {
        PlatformRuntime.network().sendToPlayer(
            Objects.requireNonNull(message, "message"),
            Objects.requireNonNull(player, "player")
        );
    }

    public static void sendToServer(Object message) {
        PlatformRuntime.network().sendToServer(Objects.requireNonNull(message, "message"));
    }

    public static void sendToAllWatching(Object message, LevelChunk chunk) {
        PlatformRuntime.network().sendToTrackingChunk(
            Objects.requireNonNull(message, "message"),
            Objects.requireNonNull(chunk, "chunk")
        );
    }

    public static void sendToDimension(Object message, ResourceKey<Level> dimension) {
        PlatformRuntime.network().sendToDimension(
            Objects.requireNonNull(message, "message"),
            Objects.requireNonNull(dimension, "dimension")
        );
    }
}
