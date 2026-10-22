/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.core.marker.volume;

import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.MessageManager;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Re-enable world editing tick and periodic initial synchronization of stored Volume Boxes. */
public final class BCCoreVolumeBoxEvents {
    private static boolean registered;
    private static final java.util.Map<ServerLevel, Integer> PLAYER_COUNTS = new java.util.WeakHashMap<>();
    private BCCoreVolumeBoxEvents() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PlatformEvents.levelTick(BCEvents.Phase.END, event -> {
            if (event.side() != BCNetworkSide.SERVER || !(event.level() instanceof ServerLevel server)) return;
            WorldSavedDataVolumeBoxes boxes = WorldSavedDataVolumeBoxes.get(server);
            boxes.tick();
            // Only send full snapshots on player-count changes and periodic recovery, not every five seconds.
            int playerCount = server.players().size();
            Integer previous = PLAYER_COUNTS.put(server, playerCount);
            if (playerCount > 0 && !boxes.volumeBoxes.isEmpty()
                && (previous == null || previous != playerCount || server.getGameTime() % 1200 == 0)) {
                MessageVolumeBoxes.sendSnapshotToDimension(boxes.volumeBoxes, server.dimension());
            }
        });
        PlatformEvents.levelUnload(event -> {
            if (event.getLevel() instanceof ServerLevel server) {
                PLAYER_COUNTS.remove(server);
            }
            if (event.getLevel() instanceof Level level && level.isClientSide) {
                ClientVolumeBoxes.INSTANCE.volumeBoxes.clear();
            }
        });
    }
}
