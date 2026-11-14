/* BuildCraft Transport gameplay events, loader neutral. MPL-2.0. */
package buildcraft.transport;

import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.transport.net.PipeItemMessageQueue;
import buildcraft.transport.wire.WorldSavedDataWireSystems;
import net.minecraft.world.level.Level;

public final class BCTransportEventDist {
    private static boolean gameplayEventsRegistered;
    private BCTransportEventDist() { }

    public static void onWorldTick(BCEvents.LevelTick event) {
        Level level = event.getLevel();
        if (!level.isClientSide() && level.getServer() != null)
            WorldSavedDataWireSystems.get(level).tick();
    }
    public static void onServerTick(BCEvents.ServerTick event) {
        PipeItemMessageQueue.serverTick();
    }
    public static void onChunkWatch(BCEvents.ChunkWatch event) {
        WorldSavedDataWireSystems.get(event.getLevel()).changedPlayers.add(event.getPlayer());
    }
    public static synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) return;
        gameplayEventsRegistered = true;
        PlatformEvents.levelTick(BCEvents.Phase.END, BCTransportEventDist::onWorldTick);
        PlatformEvents.serverTick(BCEvents.Phase.START, BCTransportEventDist::onServerTick);
        PlatformEvents.serverTick(BCEvents.Phase.END, BCTransportEventDist::onServerTick);
        PlatformEvents.chunkWatch(BCTransportEventDist::onChunkWatch);
    }
}
