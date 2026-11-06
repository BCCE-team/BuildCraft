/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.client.render;

import java.util.Collections;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.builders.snapshot.ITileForSnapshotBuilder;
import buildcraft.builders.snapshot.SnapshotBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
//? if <1.20 {
import net.minecraft.client.renderer.block.model.ItemTransforms.TransformType;
//?}
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
//? if >=1.20 {
/*?
import net.minecraft.world.item.ItemDisplayContext;
?*/
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
public class RenderSnapshotBuilder {
    public static <T extends ITileForSnapshotBuilder> void render(
            SnapshotBuilder<T> builder, Level world, BlockPos tilePos, float partialTicks,
            PoseStack matrix, MultiBufferSource buffer, ItemRenderer itemRenderer) {
        renderTasks(builder, world, tilePos, partialTicks, matrix, buffer, itemRenderer, false);
    }

    /** Use the BCU small flying block animation for Filler tasks, not full item icons. */
    public static <T extends ITileForSnapshotBuilder> void renderFiller(
            SnapshotBuilder<T> builder, Level world, BlockPos tilePos, float partialTicks,
            PoseStack matrix, MultiBufferSource buffer, ItemRenderer itemRenderer) {
        renderTasks(builder, world, tilePos, partialTicks, matrix, buffer, itemRenderer, true);
    }

    private static <T extends ITileForSnapshotBuilder> void renderTasks(
            SnapshotBuilder<T> builder, Level world, BlockPos tilePos, float partialTicks,
            PoseStack matrix, MultiBufferSource buffer, ItemRenderer itemRenderer, boolean filler) {
        if (builder == null || world == null) return;
        matrix.pushPose();
        try {
            matrix.translate(-tilePos.getX(), -tilePos.getY(), -tilePos.getZ());
            for (SnapshotBuilder<T>.PlaceTask task : builder.clientPlaceTasks) {
                if (task.pos == null || task.items == null) continue;
                Vec3 current = builder.getPlaceTaskItemPos(task);
                Vec3 previous = builder.prevClientPlaceTasks.stream()
                    .filter(other -> task.pos.equals(other.pos))
                    .map(builder::getPlaceTaskItemPos).findFirst().orElse(current);
                Vec3 position = previous.lerp(current, partialTicks);
                matrix.pushPose();
                try {
                    matrix.translate(position.x, position.y, position.z);
                    int seed = 0;
                    for (ItemStack item : task.items) {
                        if (item == null || item.isEmpty()) continue;
                        matrix.pushPose();
                        try {
                            if (filler) matrix.scale(0.30F, 0.30F, 0.30F);
                            itemRenderer.renderStatic(item,
                                //? if <1.20 {
                                TransformType.GROUND,
                                //?} else {
                                /*?
                                ItemDisplayContext.FIXED,
                                ?*/
                                //?}
                                15728640, OverlayTexture.NO_OVERLAY,
                                matrix, buffer,
                                //? if >=1.20 {
                                /*?
                                world,
                                ?*/
                                //?}
                                seed++);
                        } finally {
                            matrix.popPose();
                        }
                    }
                } finally {
                    matrix.popPose();
                }
            }
        } finally {
            matrix.popPose();
        }
    }
}
