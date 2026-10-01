/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
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
    	
    	LaserRenderer_BC8.setupLaserRenderState();
    	BufferBuilder bb = Tesselator.getInstance().getBuilder();
    	bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        ClientVolumeBoxes.INSTANCE.volumeBoxes.forEach(volumeBox -> {
            // Volume boxes are synchronized independently of client chunk lifetime. Keep the cached box so it can
            // reappear intact after a render-distance unload, but never draw it while any chunk intersecting the
            // box is absent from the client. Otherwise unfogged laser edges remain visible in the sky long after the
            // terrain has unloaded.
            if (!isBoxFullyLoaded(volumeBox)) {
                return;
            }
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
            LaserBoxRenderer.renderLaserBoxDynamic(volumeBox.box, type, pose.last().pose(), pose.last().normal(), bb, false);

            volumeBox.addons.values().forEach(addon ->
                ((IFastAddonRenderer<Addon>) addon.getRenderer()).renderAddonFast(addon, player, partialTicks, bb)
            );
        });
        LaserRenderer_BC8.setupLaserRenderState();
        Tesselator.getInstance().end();
		
	}

    private static boolean isBoxFullyLoaded(buildcraft.core.marker.volume.VolumeBox volumeBox) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || volumeBox == null || volumeBox.box == null
            || volumeBox.box.min() == null || volumeBox.box.max() == null) {
            return false;
        }

        BlockPos min = volumeBox.box.min();
        BlockPos max = volumeBox.box.max();
        int minChunkX = Math.min(min.getX(), max.getX()) >> 4;
        int maxChunkX = Math.max(min.getX(), max.getX()) >> 4;
        int minChunkZ = Math.min(min.getZ(), max.getZ()) >> 4;
        int maxChunkZ = Math.max(min.getZ(), max.getZ()) >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                    return false;
                }
            }
        }
        return true;
    }

}
