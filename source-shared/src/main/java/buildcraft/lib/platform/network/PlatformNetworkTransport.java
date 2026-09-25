package buildcraft.lib.platform.network;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Loader-owned transport for BuildCraft packets.
 *
 * <p>Message codecs and handlers live above this boundary. Implementations are responsible only for delivering an
 * already registered message through the loader's networking API.</p>
 */
public interface PlatformNetworkTransport {
    void sendToAll(Object message);

    void sendToPlayer(Object message, ServerPlayer player);

    void sendToServer(Object message);

    void sendToTrackingChunk(Object message, LevelChunk chunk);

    void sendToDimension(Object message, ResourceKey<Level> dimension);
}
