/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.transport.client;

import buildcraft.transport.BCTransport;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** The visual clock is never rewound by server game-time corrections. */
@EventBusSubscriber(modid = BCTransport.MODID, value = Dist.CLIENT)
public final class ClientPipeVisualClock {
    private static long ticks;
    private ClientPipeVisualClock() { }

    public static long ticks() { return ticks; }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !minecraft.isPaused()) ticks++;
    }
}
