/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
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
    @Override
    public void render(PoseStack pose, Matrix4f matrix, Player player, float partialTicks) {
        if (pos == null || face == null) {
            return;
        }
        //? if >=1.21.9 {
        VertexConsumer bb = net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource()
            .getBuffer(buildcraft.lib.compat.RenderCompat.solid());
        //?} else {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        //?}
        VolumeUtil.iterateCone(player.level(), pos, face, 6, true, (world, start, p, visible) -> {
            int colour = visible ? COLOUR_VISIBLE : COLOUR_NOT_VISIBLE;
            DebugRenderHelper.renderSmallCuboid(pose, matrix, bb, p, colour);
        });
        //? if <1.21.9 {
        var mesh = bb.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        //?}
    }
}
