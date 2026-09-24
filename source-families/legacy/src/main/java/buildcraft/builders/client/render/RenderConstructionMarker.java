/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.builders.client.render;

import javax.annotation.Nonnull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import buildcraft.builders.tile.TileConstructionMarker;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.client.render.laser.LaserData_BC8;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.misc.data.Box;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public class RenderConstructionMarker implements BlockEntityRenderer<TileConstructionMarker> {
    protected final ItemRenderer itemRenderer;

    public RenderConstructionMarker(BlockEntityRendererProvider.Context context) {
        itemRenderer = context.getItemRenderer();
    }

    @Override
    public void render(@Nonnull TileConstructionMarker tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int light, int overlay) {
        Minecraft.getInstance().getProfiler().push("bc");
        Minecraft.getInstance().getProfiler().push("construction_marker");

        matrix.pushPose();
        VertexConsumer bb = buffer.getBuffer(RenderType.cutout());
        BlockPos pos = tile.getBlockPos();
        Matrix4f pose = matrix.last().pose();
        Matrix3f normal = matrix.last().normal();
        Box box = tile.getBox();
        matrix.translate(-pos.getX(), -pos.getY(), -pos.getZ());
        LaserBoxRenderer.renderLaserBoxDynamic(box, BuildCraftLaserManager.STRIPES_WRITE, pose, normal, bb, true);
        renderDirectionLaser(tile, pose, normal, bb);
        matrix.translate(pos.getX(), pos.getY(), pos.getZ());

        ItemStack blueprint = tile.getBlueprintStack();
        if (!blueprint.isEmpty()) {
            matrix.pushPose();
            matrix.translate(0.5D, 0.45D, 0.5D);
            matrix.scale(1.5F, 1.5F, 1.5F);
            itemRenderer.renderStatic(
                blueprint,
                ItemDisplayContext.GROUND,
                light,
                OverlayTexture.NO_OVERLAY,
                matrix,
                buffer,
                tile.getLevel(),
                0
            );
            matrix.popPose();
        }

        if (tile.getBuilder() != null) {
            RenderSnapshotBuilder.render(tile.getBuilder(), tile.getLevel(), tile.getBlockPos(), partialTicks, matrix, buffer, itemRenderer);
        }
        matrix.popPose();

        Minecraft.getInstance().getProfiler().pop();
        Minecraft.getInstance().getProfiler().pop();
    }


    private static void renderDirectionLaser(TileConstructionMarker tile, Matrix4f pose, Matrix3f normal, VertexConsumer bb) {
        Direction direction = tile.getDirection();
        if (direction == null) {
            return;
        }

        Vec3 start = Vec3.atCenterOf(tile.getBlockPos());
        Vec3 end = start.add(Vec3.atLowerCornerOf(direction.getNormal()).scale(0.5D));
        LaserData_BC8 data = new LaserData_BC8(BuildCraftLaserManager.STRIPES_WRITE_DIRECTION, start, end, 1 / 32.0, true);
        LaserRenderer_BC8.renderLaserDynamic(pose, normal, data, bb);
    }

    @Override
    public boolean shouldRenderOffScreen(TileConstructionMarker tile) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
