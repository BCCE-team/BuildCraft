package buildcraft.transport;

import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.transport.net.PipeItemMessageQueue;
import buildcraft.transport.wire.WorldSavedDataWireSystems;

/** Server gameplay hooks shared by Forge and Fabric legacy transport. */
public final class BCTransportServerEvents {
    private static boolean registered;

    private BCTransportServerEvents() {
    }

    public static synchronized void registerGameplayEvents() {
        if (registered) {
            return;
        }
        registered = true;
        PlatformEvents.levelTick(BCEvents.Phase.END, BCTransportServerEvents::onWorldTick);
        PlatformEvents.levelTick(BCEvents.Phase.START, BCTransportServerEvents::onWorldTick);
        PlatformEvents.serverTick(BCEvents.Phase.END, BCTransportServerEvents::onServerTick);
        PlatformEvents.serverTick(BCEvents.Phase.START, BCTransportServerEvents::onServerTick);
        PlatformEvents.chunkWatch(BCTransportServerEvents::onChunkWatch);
    }

    private static void onWorldTick(BCEvents.LevelTick event) {
        if (!event.level().isClientSide && event.level().getServer() != null) {
            WorldSavedDataWireSystems.get(event.level()).tick();
        }
    }

    private static void onServerTick(BCEvents.ServerTick event) {
        PipeItemMessageQueue.serverTick();
    }

    private static void onChunkWatch(BCEvents.ChunkWatch event) {
        WorldSavedDataWireSystems.get(event.getLevel()).changedPlayers.add(event.getPlayer());
    }
}
