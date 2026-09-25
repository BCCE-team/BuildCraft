package buildcraft.lib.platform.runtime;

import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.network.PlatformNetworkTransport;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;

/** NeoForge implementation of BuildCraft's internal runtime boundary. */
public final class NeoForgeRuntimePlatform implements RuntimePlatform {
    public static final NeoForgeRuntimePlatform INSTANCE = new NeoForgeRuntimePlatform();

    private static final PlatformNetworkTransport NETWORK = new PlatformNetworkTransport() {
        @Override
        public void sendToAll(Object message) {
            MessageManager.sendToAll(message);
        }

        @Override
        public void sendToPlayer(Object message, ServerPlayer player) {
            MessageManager.sendTo(message, player);
        }

        @Override
        public void sendToServer(Object message) {
            MessageManager.sendToServer(message);
        }

        @Override
        public void sendToTrackingChunk(Object message, LevelChunk chunk) {
            MessageManager.sendToAllWatching(message, chunk);
        }

        @Override
        public void sendToDimension(Object message, ResourceKey<Level> dimension) {
            MessageManager.sendToDimension(message, dimension);
        }
    };

    private NeoForgeRuntimePlatform() {
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public PlatformNetworkTransport network() {
        return NETWORK;
    }
}
