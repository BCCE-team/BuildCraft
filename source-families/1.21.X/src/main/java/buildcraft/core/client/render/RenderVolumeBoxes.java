/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.core.marker.volume.Addon;
import buildcraft.core.marker.volume.ClientVolumeBoxes;
import buildcraft.core.marker.volume.IFastAddonRenderer;
import buildcraft.core.marker.volume.Lock;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.client.render.laser.LaserData_BC8.LaserType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

public enum RenderVolumeBoxes implements DetachedRenderer.IDetachedRenderer {
    INSTANCE;
	
    @Override
	public void render(PoseStack pose, Matrix4f matrix, Player player, float partialTicks) {
    	
        LaserRenderer_BC8.setupLaserRenderState();
        //? if >=1.21.9 {
        // The 1.21.11 compatibility BufferUploader is intentionally inert. Append volume-box laser/addon
        // geometry to the live level buffer instead; DetachedRenderer flushes it after the complete batch.
        VertexConsumer bb = Minecraft.getInstance().renderBuffers().bufferSource()
            .getBuffer(buildcraft.lib.compat.RenderCompat.solid());
        //?} else {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        //?}
        ClientVolumeBoxes.INSTANCE.volumeBoxes.forEach(volumeBox -> {
            // Render loaded portions of the original edges without inventing a clipped box corner.
            LaserType type;
            if (volumeBox.isEditingBy(player)) {
                type = BuildCraftLaserManager.MARKER_VOLUME_SIGNAL;
            } else {
                type = volumeBox.getLockTargetsStream()
                    .filter(Lock.Target.TargetUsedByMachine.class::isInstance)
                    .map(Lock.Target.TargetUsedByMachine.class::cast)
                    .map(target -> target.type)
                    .map(Lock.Target.TargetUsedByMachine.EnumType::getLaserType)
                    .findFirst()
                    .orElse(BuildCraftLaserManager.MARKER_VOLUME_CONNECTED);
            }
            LaserBoxRenderer.renderVisibleLaserBoxDynamic(volumeBox.box, type, pose.last().pose(),
                pose.last().normal(), bb, false, Minecraft.getInstance().level, player.blockPosition());

        });
        //? if <1.21.9 {
        LaserRenderer_BC8.setupLaserRenderState();
        var mesh = bb.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        // The regular laser shader disables blending: a second batch is required for transparent previews.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        try {
            BufferBuilder ghostLayer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            ClientVolumeBoxes.INSTANCE.volumeBoxes.forEach(volumeBox -> {
                if (!isBoxFullyLoaded(volumeBox)) return;
                volumeBox.addons.values().forEach(addon ->
                    ((IFastAddonRenderer<Addon>) addon.getRenderer()).renderAddonFast(
                        addon, player, partialTicks, pose, ghostLayer)
                );
            });
            var ghostMesh = ghostLayer.build();
            if (ghostMesh != null) BufferUploader.drawWithShader(ghostMesh);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
        //?} else {
        // Buffered NeoForge translucency must be flushed; the detached laser flush only handles solid/cutout.
        VertexConsumer ghostLayer = Minecraft.getInstance().renderBuffers().bufferSource()
            .getBuffer(buildcraft.lib.compat.RenderCompat.translucent());
        ClientVolumeBoxes.INSTANCE.volumeBoxes.forEach(volumeBox -> {
            if (!isBoxFullyLoaded(volumeBox)) return;
            volumeBox.addons.values().forEach(addon ->
                ((IFastAddonRenderer<Addon>) addon.getRenderer()).renderAddonFast(
                    addon, player, partialTicks, pose, ghostLayer)
            );
        });
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch(
            buildcraft.lib.compat.RenderCompat.translucent());
        //?}
		
	}

    private static boolean isBoxFullyLoaded(buildcraft.core.marker.volume.VolumeBox volumeBox) {
        ClientLevel level = Minecraft.getInstance().level;
        Player player = Minecraft.getInstance().player;
        if (level == null || player == null || volumeBox == null || volumeBox.box == null
                || volumeBox.box.min() == null || volumeBox.box.max() == null) return false;
        BlockPos min = volumeBox.box.min();
        BlockPos max = volumeBox.box.max();
        int px = player.getBlockX() >> 4;
        int pz = player.getBlockZ() >> 4;
        int[] xs = { min.getX() >> 4, max.getX() >> 4 };
        int[] zs = { min.getZ() >> 4, max.getZ() >> 4 };
        for (int x : xs) for (int z : zs) {
            if (Math.abs((long) x - px) <= 16 && Math.abs((long) z - pz) <= 16
                    && level.getChunkSource().getChunkNow(x, z) != null) return true;
        }
        return false;
    }

}
