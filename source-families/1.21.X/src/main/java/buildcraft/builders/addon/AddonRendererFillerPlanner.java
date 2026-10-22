/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.addon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.core.marker.volume.IFastAddonRenderer;
import buildcraft.core.marker.volume.AddonQuadRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import buildcraft.builders.BCBuildersSprites;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AddonRendererFillerPlanner implements IFastAddonRenderer<AddonFillerPlanner> {
    @Override
    public void renderAddonFast(AddonFillerPlanner addon, Player player, float partialTicks, PoseStack pose, VertexConsumer vb) {
        if (addon.buildingInfo == null) {
            return;
        }
//        Minecraft.getInstance().getProfiler().push("filler_planner");

//        Minecraft.getInstance().getProfiler().push("iter");
        // Restrict the hologram to the nearby portion of the planned area. A 256x256x256 Volume Box
        // must not allocate or sort millions of BlockPos objects every render frame.
        BlockPos origin = player.blockPosition();
        BlockPos min = addon.buildingInfo.box.min();
        BlockPos max = addon.buildingInfo.box.max();
        int minX = Math.max(min.getX(), origin.getX() - 32);
        int minY = Math.max(min.getY(), origin.getY() - 24);
        int minZ = Math.max(min.getZ(), origin.getZ() - 32);
        int maxX = Math.min(max.getX(), origin.getX() + 32);
        int maxY = Math.min(max.getY(), origin.getY() + 24);
        int maxZ = Math.min(max.getZ(), origin.getZ() + 32);
        if (minX > maxX || minY > maxY || minZ > maxZ) return;
        List<BlockPos> list = new ArrayList<>();
        int inspected = 0;
        for (BlockPos p : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (++inspected > 32_768 || list.size() >= 1_024) break;
            //? if <1.20 {
            if (!player.level.isEmptyBlock(p)) continue;
            //?} else {
            if (!player.level().isEmptyBlock(p)) continue;
            //?}
            int index = addon.buildingInfo.getSnapshot().posToIndex(addon.buildingInfo.fromWorld(p));
            if (index >= 0 && addon.buildingInfo.getSnapshot().data.get(index)) list.add(p.immutable());
        }
//        Minecraft.getInstance().getProfiler().pop();

  //      Minecraft.getInstance().getProfiler().push("sort");
        list.sort(Comparator.<BlockPos>comparingDouble(p -> player.distanceToSqr(Vec3.atLowerCornerOf(p))).reversed());
  //      Minecraft.getInstance().getProfiler().pop();

    //    Minecraft.getInstance().getProfiler().push("render");
        TextureAtlasSprite s = BCBuildersSprites.FILLER_PREVIEW_WHITE.getSprite();
        if (s == null) return;
        for (BlockPos p : list) {
            AABB bb = new AABB(p).inflate(-0.1);
            AddonQuadRenderer.box(vb, pose, bb, s, 127);
        }
//        Minecraft.getInstance().getProfiler().pop();

//        Minecraft.getInstance().getProfiler().pop();
    }
}
