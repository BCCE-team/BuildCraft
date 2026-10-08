//? source if >=26.3
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.silicon.client.render;

import java.util.List;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import buildcraft.lib.client.render.compat.BCWorldGeometry;
import buildcraft.lib.client.render.compat.CapturedBlockEntityRenderer.Layer;
import buildcraft.lib.debug.BCAdvDebugging;
import buildcraft.lib.debug.IAdvDebugTarget;
import buildcraft.silicon.BCSilicon;
import buildcraft.silicon.tile.TileLaser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

@EventBusSubscriber(modid = BCSilicon.MODID, value = Dist.CLIENT)
public final class SiliconDebugGeometry263 {
    private static final ContextKey<List<Layer>> GEOMETRY_KEY =
        new ContextKey<>(Identifier.fromNamespaceAndPath(BCSilicon.MODID, "debug_laser_geometry"));
    private static final ThreadLocal<Boolean> CAPTURING = ThreadLocal.withInitial(() -> false);

    private SiliconDebugGeometry263() {}

    static boolean isCapturing() {
        return CAPTURING.get();
    }

    @SubscribeEvent
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        IAdvDebugTarget target = BCAdvDebugging.INSTANCE.targetClient;
        if (!(target instanceof TileLaser laser) || !target.doesExistInWorld()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        if (player == null || player.level() != event.getLevel()) {
            return;
        }
        List<Layer> layers = BCWorldGeometry.capture(() -> {
            CAPTURING.set(true);
            try {
                laser.getDebugRenderer().render(new PoseStack(), new Matrix4f(), player,
                    event.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            } finally {
                CAPTURING.remove();
            }
        });
        if (!layers.isEmpty()) {
            event.getRenderState().setRenderData(GEOMETRY_KEY, layers);
        }
    }

    @SubscribeEvent
    public static void onSubmit(SubmitCustomGeometryEvent event) {
        List<Layer> layers = event.getLevelRenderState().getRenderData(GEOMETRY_KEY);
        if (layers == null || layers.isEmpty()) {
            return;
        }
        Vec3 camera = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        try {
            pose.translate(-camera.x, -camera.y, -camera.z);
            BCWorldGeometry.submit(layers, pose, event.getSubmitNodeCollector());
        } finally {
            pose.popPose();
        }
    }
}
