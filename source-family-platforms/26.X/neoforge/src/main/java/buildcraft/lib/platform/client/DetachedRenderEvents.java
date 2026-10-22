//? source if >=26.2
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.client;

import java.util.List;
import buildcraft.lib.BCLib;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.compat.BCWorldGeometry;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer.Layer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Native extraction/submission boundary for world-space marker and held-item geometry. */
@EventBusSubscriber(modid = BCLib.MODID, value = Dist.CLIENT)
public final class DetachedRenderEvents {
    private static final ContextKey<List<Layer>> GEOMETRY = new ContextKey<>(
        Identifier.fromNamespaceAndPath(BCLib.MODID, "detached_geometry"));

    private DetachedRenderEvents() {}

    @SubscribeEvent
    public static void extract(ExtractLevelRenderStateEvent event) {
        var player = Minecraft.getInstance().player;
        List<Layer> layers = player == null ? List.of() : DetachedRenderer.INSTANCE.extract(
            player, event.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        event.getRenderState().setRenderData(GEOMETRY, layers);
    }

    @SubscribeEvent
    public static void submit(SubmitCustomGeometryEvent event) {
        List<Layer> layers = event.getLevelRenderState().getRenderData(GEOMETRY);
        if (layers != null) BCWorldGeometry.submit(layers, event.getPoseStack(), event.getSubmitNodeCollector());
    }
}
