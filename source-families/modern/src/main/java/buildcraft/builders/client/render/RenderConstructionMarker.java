//? source if >=1.21.1
/* Copyright (c) 2017 SpaceToad and the BuildCraft team */
package buildcraft.builders.client.render;

import javax.annotation.Nonnull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import buildcraft.builders.tile.TileConstructionMarker;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.compat.LegacyBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LegacyLaserBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.client.render.laser.LaserData_BC8;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.misc.data.Box;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class RenderConstructionMarker implements LegacyBlockEntityRenderer<TileConstructionMarker>,
    LegacyLaserBlockEntityRenderer<TileConstructionMarker> {

    public RenderConstructionMarker(BlockEntityRendererProvider.Context context) {
    }

    public void render(@Nonnull TileConstructionMarker tile, float partialTicks, PoseStack matrix,
        MultiBufferSource buffer, int light, int overlay) {
        // 1.21.11 invokes submit(), not this immediate-mode compatibility method. Lasers remain here so the
        // shared LegacyLaserBlockEntityRenderer bridge can submit them as custom geometry.
        renderLasers(tile, partialTicks, matrix, buffer, light, overlay);
    }

    public void submit(LegacyRenderState<TileConstructionMarker> state, PoseStack matrix,
        SubmitNodeCollector collector, CameraRenderState cameraState) {
        LegacyBlockEntityRenderer.super.submit(state, matrix, collector, cameraState);
        TileConstructionMarker tile = state.blockEntity();
        if (tile == null || tile.getLevel() == null) return;

        matrix.pushPose();
        ItemStack blueprint = tile.getBlueprintStack();
        if (!blueprint.isEmpty()) {
            matrix.pushPose();
            matrix.translate(0.5D, 0.45D, 0.5D);
            matrix.scale(1.5F, 1.5F, 1.5F);
            ItemStackRenderState itemState = new ItemStackRenderState();
            Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                itemState, blueprint, ItemDisplayContext.GROUND, tile.getLevel(), null, 0
            );
            itemState.submit(matrix, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            matrix.popPose();
        }

        if (tile.getBuilder() != null) {
            RenderSnapshotBuilder.submit(
                tile.getBuilder(), tile.getLevel(), tile.getBlockPos(), state.partialTick(), matrix, collector
            );
        }
        matrix.popPose();
    }

    public void renderLasers(@Nonnull TileConstructionMarker tile, float partialTicks, PoseStack matrix,
        MultiBufferSource buffer, int light, int overlay) {
        matrix.pushPose();
        VertexConsumer bb = buffer.getBuffer(RenderCompat.cutout());
        BlockPos pos = tile.getBlockPos();
        Matrix4f pose = matrix.last().pose();
        Matrix3f normal = matrix.last().normal();
        Box box = tile.getBox();
        matrix.translate(-pos.getX(), -pos.getY(), -pos.getZ());
        LaserBoxRenderer.renderLaserBoxDynamic(box, BuildCraftLaserManager.STRIPES_WRITE, pose, normal, bb, true);
        renderDirectionLaser(tile, pose, normal, bb);
        matrix.popPose();
    }

    private static void renderDirectionLaser(TileConstructionMarker tile, Matrix4f pose, Matrix3f normal,
        VertexConsumer bb) {
        Direction direction = tile.getDirection();
        if (direction == null) return;
        Vec3 start = Vec3.atCenterOf(tile.getBlockPos());
        Vec3 end = start.add(Vec3.atLowerCornerOf(direction.getUnitVec3i()).scale(0.5D));
        LaserData_BC8 data = new LaserData_BC8(
            BuildCraftLaserManager.STRIPES_WRITE_DIRECTION, start, end, 1 / 32.0, true
        );
        LaserRenderer_BC8.renderLaserDynamic(pose, normal, data, bb);
    }

    public boolean shouldRenderOffScreen(TileConstructionMarker tile) {
        return true;
    }

    public int getViewDistance() {
        return 256;
    }

    public AABB getRenderBoundingBox(TileConstructionMarker tile) {
        return tile.getRenderBoundingBox();
    }
}
