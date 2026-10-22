/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.render;

import javax.annotation.Nonnull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <1.20 {
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
//?} else {
/*?
import org.joml.Matrix3f;
import org.joml.Matrix4f;
?*/
//?}

import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.laser.LaserData_BC8;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.silicon.tile.TileLaser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class RenderLaser implements BlockEntityRenderer<TileLaser> {
	private static final int MAX_POWER = BuildCraftLaserManager.POWERS.length - 1;

	public RenderLaser(BlockEntityRendererProvider.Context bpc) {
	}

	@Override
	public void render(@Nonnull TileLaser tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer,
			int light, int overlay) {

		Minecraft.getInstance().getProfiler().push("bc");
		Minecraft.getInstance().getProfiler().push("laser");
		matrix.pushPose();
		VertexConsumer bb = buffer.getBuffer(RenderType.cutout());
		BlockPos pos = tile.getBlockPos();
		BlockState bs = tile.getLevel().getBlockState(pos);
		if (bs.hasProperty(BuildCraftProperties.BLOCK_FACING_6)) {
			Direction side = bs.getValue(BuildCraftProperties.BLOCK_FACING_6);
			matrix.translate(0.5 + side.getStepX() * 0.25, 0.5 + side.getStepY() * 0.25,
					0.5 + side.getStepZ() * 0.25);
			Matrix4f pose = matrix.last().pose();
			Matrix3f normal = matrix.last().normal();
			if (tile.laserPos != null) {
				long avg = tile.getAverageClient();
				if (avg > 0) {
					avg += 200_000;
					Vec3 offset = new Vec3(0.5, 0.5, 0.5).add(new Vec3(side.step()).scale(4 / 16D));
					int index = (int) (avg * MAX_POWER / tile.getMaxPowerPerTick());
					if (index > MAX_POWER) {
						index = MAX_POWER;
					}
					LaserData_BC8 laser = new LaserData_BC8(BuildCraftLaserManager.POWERS[index],
							Vec3.atLowerCornerOf(pos).add(offset), tile.laserPos, 1 / 16D);
					LaserRenderer_BC8.renderLaserDynamic(pose, normal, laser, bb);
				}
			}
		}
		matrix.popPose();
		Minecraft.getInstance().getProfiler().pop();
		Minecraft.getInstance().getProfiler().pop();
	}
}
