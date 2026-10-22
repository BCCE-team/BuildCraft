//? source if >=1.21.1
/* Copyright (c) 2017 SpaceToad and the BuildCraft team */
package buildcraft.builders.client.render;

import java.util.List;
import javax.annotation.Nonnull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import buildcraft.builders.tile.TileBuilder;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.compat.LegacyBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LegacyLaserBlockEntityRenderer;
import buildcraft.lib.client.render.laser.LaserBoxRenderer;
import buildcraft.lib.client.render.laser.LaserData_BC8;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import buildcraft.lib.compat.LevelCompat;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.Box;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class RenderBuilder implements LegacyBlockEntityRenderer<TileBuilder>, LegacyLaserBlockEntityRenderer<TileBuilder> {
    private static final double OFFSET = 0.1;

    public RenderBuilder(BlockEntityRendererProvider.Context context) {
    }

    public void render(@Nonnull TileBuilder tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer,
        int light, int overlay) {
        renderLasers(tile, partialTicks, matrix, buffer, light, overlay);
    }

    public void submit(LegacyRenderState<TileBuilder> state, PoseStack matrix, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        LegacyBlockEntityRenderer.super.submit(state, matrix, collector, cameraState);
        TileBuilder tile = state.blockEntity();
        if (tile == null || tile.getLevel() == null || tile.getBuilder() == null) return;
        matrix.pushPose();
        RenderSnapshotBuilder.submit(
            tile.getBuilder(), tile.getLevel(), tile.getBlockPos(), state.partialTick(), matrix, collector
        );
        matrix.popPose();
    }

    public void renderLasers(@Nonnull TileBuilder tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer,
        int light, int overlay) {
        LevelCompat.profilerPush(Minecraft.getInstance(), "bc");
        LevelCompat.profilerPush(Minecraft.getInstance(), "builder");
        matrix.pushPose();
        VertexConsumer bb = buffer.getBuffer(RenderCompat.cutout());
        BlockPos pos = tile.getBlockPos();
        Matrix4f pose = matrix.last().pose();
        Matrix3f normal = matrix.last().normal();
        LevelCompat.profilerPush(Minecraft.getInstance(), "box");
        Box box = tile.getBox();
        matrix.translate(-pos.getX(), -pos.getY(), -pos.getZ());
        LaserBoxRenderer.renderLaserBoxDynamic(box, BuildCraftLaserManager.STRIPES_WRITE, pose, normal, bb, true);
        LevelCompat.profilerPopPush(Minecraft.getInstance(), "path");

        List<BlockPos> path = tile.path;
        if (path != null) {
            BlockPos last = null;
            for (BlockPos p : path) {
                if (last != null) {
                    Vec3 from = Vec3.atCenterOf(last);
                    Vec3 to = Vec3.atCenterOf(p);
                    LaserData_BC8 data = new LaserData_BC8(
                        BuildCraftLaserManager.STRIPES_WRITE_DIRECTION,
                        offset(from, to), offset(to, from), 1 / 16.1, true
                    );
                    LaserRenderer_BC8.renderLaserDynamic(pose, normal, data, bb);
                }
                last = p;
            }
        }
        LevelCompat.profilerPop(Minecraft.getInstance());
        matrix.popPose();
        LevelCompat.profilerPop(Minecraft.getInstance());
        LevelCompat.profilerPop(Minecraft.getInstance());
    }

    private static Vec3 offset(Vec3 from, Vec3 to) {
        return from.add(VecUtil.scale(to.subtract(from).normalize(), OFFSET));
    }

    public boolean shouldRenderOffScreen(TileBuilder tile) { return true; }
    public int getViewDistance() { return 256; }
    public AABB getRenderBoundingBox(TileBuilder tile) { return tile.getRenderBoundingBox(); }
}
