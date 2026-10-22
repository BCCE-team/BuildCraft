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
import net.minecraft.client.Minecraft;

public class BCBuildersEventDist {

    public static void onTickClientTick(BCEvents.ClientTick event) {
        if (!Minecraft.getInstance().isPaused()) {
            ClientArchitectTables.tick();
        }
    }
    private static boolean gameplayEventsRegistered;
    public static synchronized void registerGameplayEvents() {
        if (gameplayEventsRegistered) return;
        gameplayEventsRegistered = true;

        if (PlatformEvents.isClient()) {
            PlatformClientEvents.tick(BCEvents.Phase.END, BCBuildersEventDist::onTickClientTick);
        }
    }
}
