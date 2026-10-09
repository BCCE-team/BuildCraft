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
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
public enum PipeFlowRendererFluids implements IPipeFlowRenderer<PipeFlowFluids> {
	INSTANCE;

	private static final boolean[] sides = { true, true, true, true, true, true };
	private static final boolean[][] HIDE_FACE_SIDES = createHideFaceSides();


	private static final double UV_EPSILON = 1.0e-7;

	/**
	 * BC8 did not animate pipe fluids by switching to the vanilla FLOWING sprite. Instead it kept the
	 * frozen fluid sprite and translated its texture coordinates using the per-section client flow offset.
	 * Modern atlases cannot safely sample beyond a sprite's UV rectangle, so this renderer tiles each face
	 * at texture boundaries while applying the same offset. The geometry itself stays fixed in the pipe.
	 */
	private static void renderScrollingFluid(FluidStack fluid, double amount, double capacity, Vec3 min, Vec3 max,
			Vec3 textureOffset, VertexConsumer buffer, PoseStack.Pose pose, boolean[] sideRender, int combinedLight) {
		if (fluid.isEmpty() || amount <= 0 || capacity <= 0) {
			return;
		}
		if (sideRender == null) {
			sideRender = sides;
		}
		if (textureOffset == null) {
			textureOffset = Vec3.ZERO;
		}

		double height = Math.max(0.0, Math.min(1.0, amount / capacity));
		Vec3 realMin = min;
		Vec3 realMax = max;
		if (fluid.getFluid().getFluidType().getDensity() < 0) {
			realMin = new Vec3(min.x, max.y - (max.y - min.y) * height, min.z);
		} else {
			realMax = new Vec3(max.x, min.y + (max.y - min.y) * height, max.z);
		}
		if (realMax.x <= realMin.x || realMax.y <= realMin.y || realMax.z <= realMin.z) {
			return;
		}

		TextureAtlasSprite sprite = FluidRenderer.getFluidSprite(FluidSpriteType.FROZEN, fluid.getFluid(), fluid);
		FluidRenderer.vertex.colouri(IClientFluidTypeExtensions.of(fluid.getFluid()).getTintColor(fluid));
		FluidRenderer.vertex.lighti(combinedLight);

		for (Direction face : Direction.values()) {
			if (sideRender[face.get3DDataValue()]) {
				renderTiledFace(sprite, face, realMin, realMax, textureOffset, buffer, pose);
			}
		}
	}

	private static void renderTiledFace(TextureAtlasSprite sprite, Direction face, Vec3 min, Vec3 max,
			Vec3 offset, VertexConsumer buffer, PoseStack.Pose pose) {
		double uMin;
		double uMax;
		double vMin;
		double vMax;
		double uOffset;
		double vOffset;

		switch (face.getAxis()) {
			case Y -> {
				uMin = min.x;
				uMax = max.x;
				vMin = min.z;
				vMax = max.z;
				uOffset = offset.x;
				vOffset = offset.z;
			}
			case X -> {
				uMin = min.z;
				uMax = max.z;
				vMin = min.y;
				vMax = max.y;
				uOffset = offset.z;
				vOffset = offset.y;
			}
			case Z -> {
				uMin = min.x;
				uMax = max.x;
				vMin = min.y;
				vMax = max.y;
				uOffset = offset.x;
				vOffset = offset.y;
			}
			default -> throw new IllegalStateException("Unknown pipe-fluid face axis: " + face.getAxis());
		}

		FluidRenderer.vertex.normalf(face.getStepX(), face.getStepY(), face.getStepZ());

		for (double u0 = uMin; u0 < uMax - UV_EPSILON;) {
			int uTile = (int) Math.floor(u0 + uOffset + UV_EPSILON);
			double u1 = Math.min(uMax, uTile + 1.0 - uOffset);
			if (u1 <= u0 + UV_EPSILON) {
				uTile++;
				u1 = Math.min(uMax, uTile + 1.0 - uOffset);
			}
			for (double v0 = vMin; v0 < vMax - UV_EPSILON;) {
				int vTile = (int) Math.floor(v0 + vOffset + UV_EPSILON);
				double v1 = Math.min(vMax, vTile + 1.0 - vOffset);
				if (v1 <= v0 + UV_EPSILON) {
					vTile++;
					v1 = Math.min(vMax, vTile + 1.0 - vOffset);
				}

				double tu0 = clampUv(u0 + uOffset - uTile);
				double tu1 = clampUv(u1 + uOffset - uTile);
				double tv0 = clampUv(v0 + vOffset - vTile);
				double tv1 = clampUv(v1 + vOffset - vTile);
				renderFaceCell(sprite, face, min, max, u0, u1, v0, v1, tu0, tu1, tv0, tv1, buffer, pose);
				v0 = v1;
			}
			u0 = u1;
		}
	}

	private static double clampUv(double value) {
		return Math.max(0.0, Math.min(1.0, value));
	}

	private static void renderFaceCell(TextureAtlasSprite sprite, Direction face, Vec3 min, Vec3 max,
			double u0, double u1, double v0, double v1, double tu0, double tu1, double tv0, double tv1,
			VertexConsumer buffer, PoseStack.Pose pose) {
		boolean invertU = face == Direction.EAST || face == Direction.NORTH;
		boolean invertV = face != Direction.UP;
		if (invertU) {
			tu0 = 1.0 - tu0;
			tu1 = 1.0 - tu1;
		}
		if (invertV) {
			tv0 = 1.0 - tv0;
			tv1 = 1.0 - tv1;
		}

		switch (face) {
			case UP -> {
				emitVertex(sprite, u0, max.y, v1, tu0, tv1, buffer, pose);
				emitVertex(sprite, u1, max.y, v1, tu1, tv1, buffer, pose);
				emitVertex(sprite, u1, max.y, v0, tu1, tv0, buffer, pose);
				emitVertex(sprite, u0, max.y, v0, tu0, tv0, buffer, pose);
			}
			case DOWN -> {
				emitVertex(sprite, u0, min.y, v0, tu0, tv0, buffer, pose);
				emitVertex(sprite, u1, min.y, v0, tu1, tv0, buffer, pose);
				emitVertex(sprite, u1, min.y, v1, tu1, tv1, buffer, pose);
				emitVertex(sprite, u0, min.y, v1, tu0, tv1, buffer, pose);
			}
			case WEST -> {
				emitVertex(sprite, min.x, v0, u0, tu0, tv0, buffer, pose);
				emitVertex(sprite, min.x, v0, u1, tu1, tv0, buffer, pose);
				emitVertex(sprite, min.x, v1, u1, tu1, tv1, buffer, pose);
				emitVertex(sprite, min.x, v1, u0, tu0, tv1, buffer, pose);
			}
			case EAST -> {
				emitVertex(sprite, max.x, v1, u0, tu0, tv1, buffer, pose);
				emitVertex(sprite, max.x, v1, u1, tu1, tv1, buffer, pose);
				emitVertex(sprite, max.x, v0, u1, tu1, tv0, buffer, pose);
				emitVertex(sprite, max.x, v0, u0, tu0, tv0, buffer, pose);
			}
			case NORTH -> {
				emitVertex(sprite, u0, v1, min.z, tu0, tv1, buffer, pose);
				emitVertex(sprite, u1, v1, min.z, tu1, tv1, buffer, pose);
				emitVertex(sprite, u1, v0, min.z, tu1, tv0, buffer, pose);
				emitVertex(sprite, u0, v0, min.z, tu0, tv0, buffer, pose);
			}
			case SOUTH -> {
				emitVertex(sprite, u0, v0, max.z, tu0, tv0, buffer, pose);
				emitVertex(sprite, u1, v0, max.z, tu1, tv0, buffer, pose);
				emitVertex(sprite, u1, v1, max.z, tu1, tv1, buffer, pose);
				emitVertex(sprite, u0, v1, max.z, tu0, tv1, buffer, pose);
			}
		}
	}

	private static void emitVertex(TextureAtlasSprite sprite, double x, double y, double z, double u, double v,
			VertexConsumer buffer, PoseStack.Pose pose) {
		FluidRenderer.vertex.positiond(x, y, z);
		FluidRenderer.vertex.texf(sprite.getU((float) u), sprite.getV((float) v));
		FluidRenderer.vertex.renderAsBlock(pose.pose(), pose.normal(), buffer);
	}

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
			Vec3 min, Vec3 max, Vec3 textureOffset, VertexConsumer buffer, PoseStack.Pose pose, int combinedLight) {
		Axis axis = face.getAxis();
		boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
		double outerEdge = VecUtil.getValue(positive ? max : min, axis);
		if ((positive && outerEdge <= 1.0) || (!positive && outerEdge >= 0.0)) {
			renderScrollingFluid(fluid, amount, capacity, min, max, textureOffset, buffer, pose, sides, combinedLight);
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

		renderScrollingFluid(fluid, amount, capacity, innerMin, innerMax, textureOffset, buffer, pose,
			HIDE_FACE_SIDES[face.get3DDataValue()], combinedLight);
		renderScrollingFluid(fluid, amount, capacity, outerMin, outerMax, textureOffset, buffer, pose,
			HIDE_FACE_SIDES[face.getOpposite().get3DDataValue()], combinedLight);
	}
	@Override
	public void render(PipeFlowFluids flow, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int lightc,
			int combinedOverlay) {
		FluidStack forRender = flow.getFluidStackForRender();
		if (forRender.isEmpty()) {
			return;
		}
		VertexConsumer fluidBuffer = buffer.getBuffer(RenderType.translucent());

		double[] amounts = flow.getAmountsForRender(partialTicks);
		Vec3[] offsets = flow.getOffsetsForRender(partialTicks);

		int blocklight = forRender.getFluid().getFluidType().getLightLevel();// to debug
		IPipeHolder holder = flow.pipe.getHolder();
		int combinedLight = holder.getPipeWorld().getBrightness(LightLayer.SKY, holder.getPipePos())<<20|blocklight<<4 ;

		FluidRenderer.vertex.lighti(combinedLight);
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
			Vec3 textureOffset = offsets[face.get3DDataValue()];
			if (textureOffset == null) textureOffset = Vec3.ZERO;
			renderConnectionFluid(forRender, renderAmount, renderCapacity, face, min, max, textureOffset, fluidBuffer,
				matrix.last(), combinedLight);
		}

		double amount = amounts[EnumPipePart.CENTER.getIndex()];
		Vec3 centerOffset = offsets[EnumPipePart.CENTER.getIndex()];
		if (centerOffset == null) centerOffset = Vec3.ZERO;

		double horizPos = 0.26;


		if (horizontal | !vertical) {
			Vec3 min = new Vec3(0.26, 0.26, 0.26);
			Vec3 max = new Vec3(0.74, 0.74, 0.74);

			renderScrollingFluid(forRender, amount, flow.capacity, min, max, centerOffset, fluidBuffer, matrix.last(), sides, combinedLight);
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

			renderScrollingFluid(forRender, 1, 1, min, max, centerOffset, fluidBuffer, matrix.last(), sides, combinedLight);
			
		}

	}
}
