/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <1.20 {
import com.mojang.math.Matrix4f;
//?} else {
/*?
import org.joml.Matrix4f;
?*/
//?}

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
        // World-space outlines participate in the same shader pipeline as entity cutout geometry.
        // Avoid Tesselator.end(): its raw shader draw is invisible in Oculus gbuffer passes.
        VertexConsumer outlines = LaserRenderer_BC8.worldLaserBuffer();
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
                pose.last().normal(), outlines, false, Minecraft.getInstance().level, player.blockPosition());
        });

        // Retain translucent previews in a separate native layer; it enables correct blend,
        // depth-mask and shader routing without mutating global RenderSystem state.
        VertexConsumer ghosts = LaserRenderer_BC8.worldPreviewBuffer();
        ClientVolumeBoxes.INSTANCE.volumeBoxes.forEach(volumeBox -> {
            if (!isBoxFullyLoaded(volumeBox)) return;
            volumeBox.addons.values().forEach(addon ->
                ((IFastAddonRenderer<Addon>) addon.getRenderer()).renderAddonFast(
                    addon, player, partialTicks, pose, ghosts)
            );
        });
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
