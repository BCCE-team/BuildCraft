/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.compat.BCWorldGeometry;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

/**
 * Mirrors the NeoForge 26.3 extract/submit pipeline using Fabric's level
 * rendering events. No mutable ClientLevel, Player, or tile is retained by
 * the draw callback; only recorded render-layer vertices survive extraction.
 */
public final class CoreWorldGeometry {
    private static final RenderStateDataKey<List<CapturedBlockEntityRenderer.Layer>> GEOMETRY =
        RenderStateDataKey.create();
    private static boolean registered;

    private CoreWorldGeometry() {}

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        LevelExtractionEvents.END_EXTRACTION.register(context -> {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            Player player = mc.player;
            if (level == null || player == null) {
                ((FabricRenderState) context.levelState()).setData(GEOMETRY, List.of());
                return;
            }
            float partial = context.deltaTracker().getGameTimeDeltaPartialTick(false);
            List<CapturedBlockEntityRenderer.Layer> layers = BCWorldGeometry.capture(() -> {
                PoseStack pose = new PoseStack();
                Matrix4f matrix = new Matrix4f();
                LaserRenderer_BC8.setupLaserRenderState();
                RenderTickListener.captureWorld(pose, matrix, partial);
                MarkerSubmitRenderer121111.capture(level, player, pose, matrix);
                DetachedRenderer.INSTANCE.renderWorldLastEvent(pose, matrix, player, partial);
                LaserRenderer_BC8.flushStaticLasers();
            });
            ((FabricRenderState) context.levelState()).setData(GEOMETRY, layers);
        });
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            List<CapturedBlockEntityRenderer.Layer> layers =
                ((FabricRenderState) context.levelState()).getData(GEOMETRY);
            if (layers != null && !layers.isEmpty()) {
                BCWorldGeometry.submit(layers, new PoseStack(), context.submitNodeCollector());
            }
        });
    }
}
