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
    private BCCoreVolumeBoxEvents() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PlatformEvents.levelTick(BCEvents.Phase.END, event -> {
            if (event.side() != BCNetworkSide.SERVER || !(event.level() instanceof ServerLevel server)) return;
            WorldSavedDataVolumeBoxes boxes = WorldSavedDataVolumeBoxes.get(server);
            boxes.tick();
            // Sync an existing set to players joining/changing dimensions even if no one modifies the boxes.
            if (!boxes.volumeBoxes.isEmpty() && server.getGameTime() % 100 == 0) {
                MessageManager.sendToDimension(new MessageVolumeBoxes(boxes.volumeBoxes), server.dimension());
            }
        });
        PlatformEvents.levelUnload(event -> {
            if (event.getLevel() instanceof Level level && level.isClientSide) {
                ClientVolumeBoxes.INSTANCE.volumeBoxes.clear();
            }
        });
    }
}
