/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.builders;


import buildcraft.lib.platform.events.PlatformClientEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import buildcraft.lib.platform.events.BCEvents;
import buildcraft.builders.client.ClientArchitectTables;
import buildcraft.builders.client.render.ArchitectScanWorldRenderer;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.DetachedRenderer.RenderMatrixType;
import net.minecraft.client.Minecraft;

public class BCBuildersEventDist {
    private static net.minecraft.client.multiplayer.ClientLevel lastScanWorld;

    public static void onTickClientTick(BCEvents.ClientTick event) {
        // The positions are world coordinates. Never leak another world's scan overlay after disconnect.
        var world = Minecraft.getInstance().level;
        if (lastScanWorld != world) {
            ClientArchitectTables.SCANNED_BLOCKS.clear();
            lastScanWorld = world;
        }
        if (world != null && !Minecraft.getInstance().isPaused()) {
            ClientArchitectTables.tick();
        }
    }
    private static boolean gameplayEventsRegistered;
    public static synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) return;
        gameplayEventsRegistered = true;

        if (PlatformEvents.isClient()) {
            PlatformClientEvents.tick(BCEvents.Phase.END, BCBuildersEventDist::onTickClientTick);
            DetachedRenderer.INSTANCE.addRenderer(RenderMatrixType.FROM_WORLD_ORIGIN,
                ArchitectScanWorldRenderer.INSTANCE);
        }
    }
}
