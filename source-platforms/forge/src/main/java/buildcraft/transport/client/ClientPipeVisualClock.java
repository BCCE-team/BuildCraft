/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.transport.client;

import buildcraft.transport.BCTransport;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Logical clock for client-only item-pipe interpolation.
 *
 * ClientLevel's game time is corrected by server time packets (normally once
 * every 20 ticks). Such corrections must not advance, rewind or freeze the
 * local render timeline: otherwise every travelling item can blink at once.
 *
 * Forge client END ticks, rather than frame time or wall time, keep the clock
 * aligned with tile ticking and naturally freeze it while the game is paused.
 */
@Mod.EventBusSubscriber(modid = BCTransport.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientPipeVisualClock {
    private static long ticks;

    private ClientPipeVisualClock() { }

    public static long ticks() {
        return ticks;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && !minecraft.isPaused()) {
            ticks++;
        }
    }
}
