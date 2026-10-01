//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.client.render;

import buildcraft.lib.internal.core.EnumPipePart;
import buildcraft.transport.internal.pipe.IPipeFlowRenderer;
import buildcraft.transport.internal.pipe.IPipeHolder;
import buildcraft.lib.client.render.fluid.FluidRenderer;
import buildcraft.lib.client.render.fluid.FluidSpriteType;
import buildcraft.lib.misc.VecUtil;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.pipe.flow.PipeFlowFluids;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.lib.compat.RenderCompat;

public enum PipeFlowRendererFluids implements IPipeFlowRenderer<PipeFlowFluids> {
	INSTANCE;

	private static final boolean[] sides = { true, true, true, true, true, true };
	private static final boolean[][] HIDE_FACE_SIDES = createHideFaceSides();

	private static boolean[][] createHideFaceSides() {
		Direction[] directions = Direction.values();
		boolean[][] masks = new boolean[directions.length][directions.length];
		for (Direction hidden : directions) {
			for (Direction side : directions) {
				masks[hidden.get3DDataValue()][side.get3DDataValue()] = side != hidden;
			}
		}
		return masks;
	}

	private static void renderConnectionFluid(FluidStack fluid, double amount, double capacity, Direction face,
			Vec3 min, Vec3 max, VertexConsumer buffer, PoseStack.Pose pose, int combinedLight) {
		Axis axis = face.getAxis();
		boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		double outerEdge = VecUtil.getValue(positive ? max : min, axis);
		if ((positive && outerEdge <= 1.0) || (!positive && outerEdge >= 0.0)) {
			FluidRenderer.renderFluid(FluidSpriteType.FROZEN, fluid, amount, capacity, min, max,
				buffer, pose, sides, combinedLight);
			return;
		}

		Vec3 innerMin = min;
		Vec3 innerMax = max;
		Vec3 outerMin = min;
		Vec3 outerMax = max;
		if (positive) {
			innerMax = VecUtil.replaceValue(max, axis, 1.0);
			outerMin = VecUtil.replaceValue(min, axis, 1.0);
		} else {
			innerMin = VecUtil.replaceValue(min, axis, 0.0);
			outerMax = VecUtil.replaceValue(max, axis, 0.0);
		}

		FluidRenderer.renderFluid(FluidSpriteType.FROZEN, fluid, amount, capacity, innerMin, innerMax,
			buffer, pose, HIDE_FACE_SIDES[face.get3DDataValue()], combinedLight);
		FluidRenderer.renderFluid(FluidSpriteType.FROZEN, fluid, amount, capacity, outerMin, outerMax,
			buffer, pose, HIDE_FACE_SIDES[face.getOpposite().get3DDataValue()], combinedLight);
	}
	public void render(PipeFlowFluids flow, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int lightc,
			int combinedOverlay) {
		FluidStack forRender = flow.getFluidStackForRender();
		if (forRender.isEmpty()) {
			return;
		}
		VertexConsumer fluidBuffer = buffer.getBuffer(RenderCompat.translucent());
		renderFluidGeometry(flow, partialTicks, matrix.last(), fluidBuffer, forRender, combinedOverlay);
	}

	/** Native 1.21.11 submission path. Fluid geometry belongs in the transparent feature phase so alpha-bearing
	 * textures are submitted through a compatible render layer. */
	public void submit(PipeFlowFluids flow, float partialTicks, PoseStack matrix, SubmitNodeCollector collector,
			int lightc, int combinedOverlay) {
		FluidStack forRender = flow.getFluidStackForRender();
		if (forRender.isEmpty()) {
			return;
		}
		collector.submitCustomGeometry(matrix, RenderCompat.translucent(), (pose, consumer) ->
			renderFluidGeometry(flow, partialTicks, pose, consumer, forRender, combinedOverlay)
		);
	}

	private static void renderFluidGeometry(PipeFlowFluids flow, float partialTicks, PoseStack.Pose pose,
			VertexConsumer fluidBuffer, FluidStack forRender, int combinedOverlay) {
		double[] amounts = flow.getAmountsForRender(partialTicks);

		int blocklight = forRender.getFluid().getFluidType().getLightLevel();// to debug
		IPipeHolder holder = flow.pipe.getHolder();
		int combinedLight = holder.getPipeWorld().getBrightness(LightLayer.SKY, holder.getPipePos())<<20|blocklight<<4 ;

		FluidRenderer.vertex.overlay(combinedOverlay);

		boolean gas = forRender.getFluid().getFluidType().getDensity() <= 0;
		boolean horizontal = false;
		boolean vertical = flow.pipe.isConnected(gas ? Direction.DOWN : Direction.UP);

		for (Direction face : Direction.values()) {
			double size = ((Pipe) flow.pipe).getConnectedDist(face);
			if(size == 0)
				continue;
			double amount = amounts[face.get3DDataValue()];
			if (face.getAxis() != Axis.Y) {
				horizontal |= flow.pipe.isConnected(face) && amount > 0;
			}

			Vec3 center = VecUtil.offset(new Vec3(0.5, 0.5, 0.5), face, 0.245 + size / 2);
			Vec3 radius = new Vec3(0.24, 0.24, 0.24);
			radius = VecUtil.replaceValue(radius, face.getAxis(), 0.005 + size / 2);

			if (face.getAxis() == Axis.Y) {
				double perc = amount / flow.capacity;
				perc = Math.sqrt(perc);
				radius = new Vec3(perc * 0.24, radius.y, perc * 0.24);
			}

			Vec3 min = center.subtract(radius);
			Vec3 max = center.add(radius);

			double renderAmount = face.getAxis() == Axis.Y ? 1 : amount;
			double renderCapacity = face.getAxis() == Axis.Y ? 1 : flow.capacity;
			renderConnectionFluid(forRender, renderAmount, renderCapacity, face, min, max, fluidBuffer, pose, combinedLight);
		}

		double amount = amounts[EnumPipePart.CENTER.getIndex()];

		double horizPos = 0.26;


		if (horizontal | !vertical) {
			Vec3 min = new Vec3(0.26, 0.26, 0.26);
			Vec3 max = new Vec3(0.74, 0.74, 0.74);

			FluidRenderer.renderFluid(FluidSpriteType.FROZEN, forRender, amount, flow.capacity, min, max, fluidBuffer, pose, sides, combinedLight);
			horizPos += (max.y - min.y) * amount / flow.capacity;
		}

		if (vertical && horizPos < 0.74) {
			double perc = amount / flow.capacity;
			perc = Math.sqrt(perc);
			double minXZ = 0.5 - 0.24 * perc;
			double maxXZ = 0.5 + 0.24 * perc;

			double yMin = gas ? 0.26 : horizPos;
			double yMax = gas ? 1 - horizPos : 0.74;

			Vec3 min = new Vec3(minXZ, yMin, minXZ);
			Vec3 max = new Vec3(maxXZ, yMax, maxXZ);

			FluidRenderer.renderFluid(FluidSpriteType.FROZEN, forRender, 1, 1, min, max, fluidBuffer, pose, sides, combinedLight);
			
		}

	}
}
