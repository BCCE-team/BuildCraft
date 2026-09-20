//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.builders.client.render;

import java.util.Collections;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.builders.snapshot.ITileForSnapshotBuilder;
import buildcraft.builders.snapshot.SnapshotBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
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
        matrix.translate(-tilePos.getX(), -tilePos.getY(), -tilePos.getZ());
        for (SnapshotBuilder<T>.PlaceTask placeTask : snapshotBuilder.clientPlaceTasks) {
            Vec3 prevPos = snapshotBuilder.prevClientPlaceTasks.stream()
                .filter(renderTaskLocal -> renderTaskLocal.pos.equals(placeTask.pos))
                .map(snapshotBuilder::getPlaceTaskItemPos)
                .findFirst()
                .orElse(snapshotBuilder.getPlaceTaskItemPos(
                    snapshotBuilder.new PlaceTask(tilePos, Collections.emptyList(), 0L)
                ));
            Vec3 pos = prevPos.add(snapshotBuilder.getPlaceTaskItemPos(placeTask).subtract(prevPos).scale(partialTicks));

            matrix.translate(pos.x, pos.y, pos.z);
            int seed = 0;
            for (ItemStack item : placeTask.items) {
                if (item.isEmpty()) continue;
                ItemStackRenderState itemState = new ItemStackRenderState();
                Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                    itemState, item, ItemDisplayContext.GROUND, world, null, seed++
                );
                itemState.submit(matrix, collector, 15728640, OverlayTexture.NO_OVERLAY, 0);
            }
            matrix.translate(-pos.x, -pos.y, -pos.z);
        }
    }
}
