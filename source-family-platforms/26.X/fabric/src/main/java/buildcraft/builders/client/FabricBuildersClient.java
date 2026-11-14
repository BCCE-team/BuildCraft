/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.builders.client;

import buildcraft.builders.BCBuildersClientGuis;
import buildcraft.builders.BCBuildersClientRenderers;
import buildcraft.builders.BCBuildersEventDist;
import buildcraft.builders.BCBuildersItems;
import buildcraft.builders.BCBuildersSprites;
import buildcraft.builders.BCBuilders;
import buildcraft.builders.client.render.pip.BlueprintPipRenderer;
import buildcraft.builders.client.tooltip.BlueprintClientTooltip;
import buildcraft.builders.item.BlueprintTooltipData;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import buildcraft.lib.platform.client.PlatformClientRegistration;
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry;

/** Client-only Builders registrations. Never loaded by a dedicated server. */
public final class FabricBuildersClient {
    private static boolean bootstrapped;

    private FabricBuildersClient() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        BCBuilders.bootstrapClientNetwork();
        BCBuildersSprites.init();
        BCBuildersItems.registerItemModelProperties(PlatformClientRegistration.rangeProperties());
        BCBuildersClientGuis.clientInit(PlatformClientRegistration.screens());
        BCBuildersClientRenderers.register(PlatformClientRegistration.renderers());
        BCBuildersEventDist.registerGameplayEvents();
        PictureInPictureRendererRegistry.register(context -> new BlueprintPipRenderer());
        ClientTooltipComponentCallback.EVENT.register(component -> component instanceof BlueprintTooltipData data
            ? new BlueprintClientTooltip(data) : null);
    }
}
