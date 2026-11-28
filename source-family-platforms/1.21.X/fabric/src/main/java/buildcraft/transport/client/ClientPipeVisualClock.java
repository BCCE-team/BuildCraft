/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.transport.client;

import net.minecraft.client.Minecraft;

/** The visual clock is never rewound by server game-time corrections. */
public final class ClientPipeVisualClock {
    private static long ticks;
    private ClientPipeVisualClock() { }

    public static long ticks() { return ticks; }

    public static void onClientTick(Minecraft minecraft) {
        if (minecraft.level != null && !minecraft.isPaused()) ticks++;
    }
}
