/* Copyright (c) the BuildCraft team. SPDX-License-Identifier: MPL-2.0 */
package buildcraft.transport.client.render;

import buildcraft.transport.BCTransport;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge stage bridge for the loader-neutral legacy pipe alpha queue. */
@Mod.EventBusSubscriber(modid = BCTransport.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class LegacyPipeAlphaStageEvents {
    private LegacyPipeAlphaStageEvents() { }

    @SubscribeEvent
    public static void afterWorldTransparency(RenderLevelStageEvent event) {
        // Entity/BER geometry and vanilla translucent terrain must exist in the
        // framebuffer before transparent BuildCraft pipe overlays are composited.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            LegacyPipeAlphaRenderer.flush(event.getPoseStack(), event.getCamera().getPosition());
        }
    }
}
