/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Matrix4f;

import buildcraft.lib.client.render.DetachedRenderer.IDetachedRenderer;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.marker.MarkerConnection;
import buildcraft.lib.marker.MarkerSubCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public enum MarkerRenderer implements IDetachedRenderer {
    INSTANCE;

    @Override
	public void render(PoseStack pose, Matrix4f matrix, Player player, float partialTicks) {
        for (MarkerCache<? extends MarkerSubCache<?>> cache : MarkerCache.CACHES) {
            for (MarkerConnection<?> connection : cache.getSubCache(player.level).getConnections()) {
                // Keep the server-authoritative connection cached across client chunk unloads, but only render it
                // while every marker chunk is actually present on this client. This prevents unfogged marker boxes
                // from remaining visible beyond the render distance without reintroducing destructive cache pruning.
                if (!connection.getMarkerPositions().stream().allMatch(pos -> isClientChunkLoaded(player.level, pos))) {
                    continue;
                }
                connection.renderInWorld(pose, matrix);
            }
        }
    }
    private static boolean isClientChunkLoaded(Level level, BlockPos pos) {
        return level != null && level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

}
