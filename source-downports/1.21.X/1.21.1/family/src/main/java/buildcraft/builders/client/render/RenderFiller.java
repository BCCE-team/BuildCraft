/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import buildcraft.builders.tile.TileFiller;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.laser.LegacyLaserBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.phys.AABB;
public class RenderFiller implements BlockEntityRenderer<TileFiller>, LegacyLaserBlockEntityRenderer<TileFiller> {
	
	protected final ItemRenderer itemRenderer; 
	
	public RenderFiller(BlockEntityRendererProvider.Context bpc) {
		itemRenderer = bpc.getItemRenderer();
	}

    @Override
    public void render(TileFiller tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int light, int overlay) {
        if (tile.getBuilder() != null) {
            matrix.pushPose();
            RenderSnapshotBuilder.render(tile.getBuilder(), tile.getLevel(), tile.getBlockPos(), partialTicks, matrix, buffer, itemRenderer);
            matrix.popPose();
        }

        renderLasers(tile, partialTicks, matrix, buffer, light, overlay);
    }

    @Override
    public void renderLasers(TileFiller tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int light, int overlay) {
        if (tile.markerBox) {
            VertexConsumer bb = buffer.getBuffer(RenderType.cutout());
            matrix.pushPose();
            matrix.translate(-tile.getBlockPos().getX(), -tile.getBlockPos().getY(), -tile.getBlockPos().getZ());
            Matrix4f boxPose = matrix.last().pose();
            Matrix3f boxNormal = matrix.last().normal();
            LaserBoxRenderer.renderLaserBoxDynamic(tile.box, BuildCraftLaserManager.STRIPES_WRITE, boxPose, boxNormal, bb, true);
            matrix.popPose();
        }
    }

    @Override
    public boolean shouldRenderOffScreen(TileFiller te) {
        return true;
    }

    @Override
    public AABB getRenderBoundingBox(TileFiller tile) {
        return tile.getRenderBoundingBox();
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
