package buildcraft.lib;

import buildcraft.lib.debug.BCAdvDebugging;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Server-only gameplay hooks shared by Forge and Fabric legacy targets. */
public final class BCLibServerEvents {
    private static boolean registered;

    private BCLibServerEvents() {
    }

    public static synchronized void registerGameplayEvents() {
        if (registered) {
            return;
        }
        registered = true;
        PlatformEvents.entityJoin(BCLibServerEvents::onEntityJoinWorld);
        PlatformEvents.levelUnload(BCLibServerEvents::onWorldUnload);
        PlatformEvents.serverTick(BCEvents.Phase.END, BCLibServerEvents::serverTick);
    }

    private static void onEntityJoinWorld(BCEvents.EntityJoin event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MessageUtil.doDelayedServer(1, () -> MarkerCache.onPlayerJoinLevel(player));
            MessageUtil.doDelayedServer(5, () -> MarkerCache.onPlayerJoinLevel(player));
            MessageUtil.doDelayedServer(20, () -> MarkerCache.onPlayerJoinLevel(player));
            MessageUtil.doDelayedServer(60, () -> MarkerCache.onPlayerJoinLevel(player));
        }
    }

    private static void onWorldUnload(BCEvents.LevelUnload event) {
        MarkerCache.onLevelUnload(event.getLevel());
        if (event.getLevel() instanceof ServerLevel level) {
            FakePlayerProvider.INSTANCE.unloadWorld(level);
            buildcraft.lib.platform.chunk.BCChunkTickets.unloadWorld(level);
        }
    }

    private static void serverTick(BCEvents.ServerTick event) {
        if (event.phase() == BCEvents.Phase.END) {
            BCAdvDebugging.INSTANCE.onServerPostTick();
            MessageUtil.postServerTick();
        }
    }
}
