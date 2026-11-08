/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.client.render;


import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.builders.snapshot.ITileForSnapshotBuilder;
import buildcraft.builders.snapshot.SnapshotBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.model.data.ModelData;
import buildcraft.lib.client.render.laser.LaserRenderer_BC8;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
public class RenderSnapshotBuilder {
    public static <T extends ITileForSnapshotBuilder> void render(
            SnapshotBuilder<T> snapshotBuilder,
            Level world,
            BlockPos tilePos,
            float partialTicks,
            PoseStack matrix,
            MultiBufferSource buffer,
            ItemRenderer itemRenderer
    ) {
        renderTasks(snapshotBuilder, world, tilePos, partialTicks, matrix, buffer, itemRenderer, false);
    }

    /** small flying block geometry, reserved for the Filler construction animation. */
    public static <T extends ITileForSnapshotBuilder> void renderFiller(
            SnapshotBuilder<T> snapshotBuilder, Level world, BlockPos tilePos,
            float partialTicks, PoseStack matrix, MultiBufferSource buffer, ItemRenderer itemRenderer
    ) {
        renderTasks(snapshotBuilder, world, tilePos, partialTicks, matrix, buffer, itemRenderer, true);
    }

    private static <T extends ITileForSnapshotBuilder> void renderTasks(
            SnapshotBuilder<T> snapshotBuilder, Level world, BlockPos tilePos,
            float partialTicks, PoseStack matrix, MultiBufferSource buffer,
            ItemRenderer itemRenderer, boolean filler
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
            int i = 0;
            for (ItemStack item : placeTask.items) {
                if (item == null || item.isEmpty()) continue;
                if (filler && item.getItem() instanceof BlockItem blockItem) {
                    // The sprite is the actual block model, not a large item icon.
                    matrix.pushPose();
                    matrix.scale(.30F, .30F, .30F);
                    matrix.translate(-.5, -.5, -.5);
                    int packedLight = LaserRenderer_BC8.computeLightmap(pos.x, pos.y, pos.z, 0);
                    Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                        blockItem.getBlock().defaultBlockState(), matrix, buffer,
                        packedLight, OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY, null);
                    matrix.popPose();
                } else {
                    matrix.pushPose();
                    if (filler) matrix.scale(.30F, .30F, .30F);
                    itemRenderer.renderStatic(item, filler ? ItemDisplayContext.FIXED : ItemDisplayContext.GROUND,
                        15728640, OverlayTexture.NO_OVERLAY, matrix, buffer, world, i++);
                    matrix.popPose();
                }
            }
            matrix.popPose();
        }
    }
}
