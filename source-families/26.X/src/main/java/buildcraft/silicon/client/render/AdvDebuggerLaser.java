//? source if >=26.2
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.lib.client.render.DetachedRenderer;
import buildcraft.lib.debug.DebugRenderHelper;
import buildcraft.lib.misc.VolumeUtil;
import buildcraft.silicon.BCSiliconBlocks;
import buildcraft.silicon.tile.TileLaser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.lib.compat.RenderCompat;

public class AdvDebuggerLaser implements DetachedRenderer.IDetachedRenderer {
    private static final int COLOUR_VISIBLE = 0xFF_99_FF_99;
    private static final int COLOUR_NOT_VISIBLE = 0xFF_11_11_99;

    private final BlockPos pos;
    private final Direction face;

    public AdvDebuggerLaser(TileLaser tile) {
        pos = tile.getBlockPos();
        BlockState state = tile.getLevel().getBlockState(pos);
        face = state.getBlock() == BCSiliconBlocks.LASER_BLOCK.get()
            ? state.getValue(BuildCraftProperties.BLOCK_FACING_6)
            : null;
    }
    public void render(PoseStack pose, Matrix4f matrix, Player player, float partialTicks) {
        if (!SiliconDebugGeometry263.isCapturing() || pos == null || face == null) {
            return;
        }
        VertexConsumer bb = buildcraft.lib.client.render.compat.BCWorldGeometry.buffer(RenderCompat.solid());
        VolumeUtil.iterateCone(player.level(), pos, face, 6, true, (world, start, p, visible) -> {
            int colour = visible ? COLOUR_VISIBLE : COLOUR_NOT_VISIBLE;
            Matrix4f worldPose = new Matrix4f(matrix).translate(p.getX(), p.getY(), p.getZ());
            DebugRenderHelper.renderSmallCuboid(pose, worldPose, bb, p, colour);
        });
    }
}
