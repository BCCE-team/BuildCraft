//? source if ==1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.builders.client.render;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.builders.snapshot.ITileForSnapshotBuilder;
import buildcraft.builders.snapshot.SnapshotBuilder;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.phys.Vec3;

/** Native 1.21.11 snapshot item submission used by builder/filler/construction-marker renderers. */
public final class RenderSnapshotBuilder {
    private RenderSnapshotBuilder() {
    }

    public static <T extends ITileForSnapshotBuilder> void submit(
        SnapshotBuilder<T> snapshotBuilder,
        Level world,
        BlockPos tilePos,
        float partialTicks,
        PoseStack matrix,
        SubmitNodeCollector collector
    ) {
        submitTasks(snapshotBuilder, world, tilePos, partialTicks, matrix, collector, false);
    }

    /** Submit BCU-sized construction cubes without affecting Builder or marker rendering. */
    public static <T extends ITileForSnapshotBuilder> void submitFiller(
        SnapshotBuilder<T> snapshotBuilder, Level world, BlockPos tilePos,
        float partialTicks, PoseStack matrix, SubmitNodeCollector collector
    ) {
        submitTasks(snapshotBuilder, world, tilePos, partialTicks, matrix, collector, true);
    }

    private static <T extends ITileForSnapshotBuilder> void submitTasks(
        SnapshotBuilder<T> snapshotBuilder, Level world, BlockPos tilePos,
        float partialTicks, PoseStack matrix, SubmitNodeCollector collector, boolean filler
    ) {
        matrix.translate(-tilePos.getX(), -tilePos.getY(), -tilePos.getZ());
        for (SnapshotBuilder<T>.PlaceTask placeTask : snapshotBuilder.clientPlaceTasks) {
            if (placeTask.pos == null || placeTask.items == null) continue;
            Vec3 current = snapshotBuilder.getPlaceTaskItemPos(placeTask);
            Vec3 previous = snapshotBuilder.prevClientPlaceTasks.stream()
                .filter(task -> task.pos.equals(placeTask.pos))
                .map(snapshotBuilder::getPlaceTaskItemPos)
                .findFirst().orElse(current);
            Vec3 pos = previous.lerp(current, partialTicks);

            matrix.pushPose();
            matrix.translate(pos.x, pos.y, pos.z);
            if (filler) matrix.scale(.30F, .30F, .30F);
            int seed = 0;
            for (ItemStack item : placeTask.items) {
                if (item == null || item.isEmpty()) continue;
                if (filler && item.getItem() instanceof BlockItem blockItem
                    && blockItem.getBlock().defaultBlockState().getRenderShape()
                        == RenderShape.MODEL) {
                    // 1.21.1 draws the installed block model for flying Filler
                    // cubes. 1.21.11 must submit that SAME model instead of
                    // the inventory item (lever/rail/torch items are different).
                    matrix.pushPose();
                    matrix.translate(-0.5F, -0.5F, -0.5F);
                    int packedLight = LaserRenderer_BC8.computeLightmap(pos.x, pos.y, pos.z, 0);
                    collector.submitBlock(matrix, blockItem.getBlock().defaultBlockState(),
                        packedLight, OverlayTexture.NO_OVERLAY, 0);
                    matrix.popPose();
                } else {
                    ItemStackRenderState itemState = new ItemStackRenderState();
                    Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                        itemState, item, filler ? ItemDisplayContext.FIXED : ItemDisplayContext.GROUND,
                        world, null, seed++
                    );
                    itemState.submit(matrix, collector, 15728640, OverlayTexture.NO_OVERLAY, 0);
                }
            }
            matrix.popPose();
        }
    }
}
