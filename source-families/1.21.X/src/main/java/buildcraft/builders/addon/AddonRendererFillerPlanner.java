/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.addon;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.core.marker.volume.IFastAddonRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class AddonRendererFillerPlanner implements IFastAddonRenderer<AddonFillerPlanner> {
    @Override
    public void renderAddonFast(AddonFillerPlanner addon, Player player, float partialTicks, VertexConsumer vb) {
        if (addon.buildingInfo == null) {
            return;
        }
//        Minecraft.getInstance().getProfiler().push("filler_planner");

//        Minecraft.getInstance().getProfiler().push("iter");
        List<BlockPos> list = StreamSupport.stream(
            BlockPos.betweenClosed(addon.buildingInfo.box.min(), addon.buildingInfo.box.max()).spliterator(),
            false
        )
            .filter(blockPos ->
                addon.buildingInfo.getSnapshot().data.get(
                    addon.buildingInfo.getSnapshot().posToIndex(
                        addon.buildingInfo.fromWorld(blockPos)
                    )
                )
            )
            .filter(player.level()::isEmptyBlock)
            .collect(Collectors.toCollection(ArrayList::new));
//        Minecraft.getInstance().getProfiler().pop();

  //      Minecraft.getInstance().getProfiler().push("sort");
        list.sort(Comparator.<BlockPos>comparingDouble(p -> player.distanceToSqr(Vec3.atLowerCornerOf(p))).reversed());
  //      Minecraft.getInstance().getProfiler().pop();

    //    Minecraft.getInstance().getProfiler().push("render");
        for (BlockPos p : list) {
            AABB bb = new AABB(p).inflate(-0.1);
            TextureAtlasSprite s = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(ResourceLocation.withDefaultNamespace("block/quartz_block_top"));//ModelLoader.White.INSTANCE;

            vb.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.minZ).setColor(204, 204, 204, 127).setUv(s.getU0(), s.getV0()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.minZ).setColor(204, 204, 204, 127).setUv(s.getU0(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.minZ).setColor(204, 204, 204, 127).setUv(s.getU1(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.minX, (float) bb.minY, (float) bb.minZ).setColor(204, 204, 204, 127).setUv(s.getU1(), s.getV0()).setLight(240);

            vb.addVertex((float) bb.minX, (float) bb.minY, (float) bb.maxZ).setColor(204, 204, 204, 127).setUv(s.getU0(), s.getV0()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.maxZ).setColor(204, 204, 204, 127).setUv(s.getU0(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.maxZ).setColor(204, 204, 204, 127).setUv(s.getU1(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.maxZ).setColor(204, 204, 204, 127).setUv(s.getU1(), s.getV0()).setLight(240);

            vb.addVertex((float) bb.minX, (float) bb.minY, (float) bb.minZ).setColor(127, 127, 127, 127).setUv(s.getU0(), s.getV0()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.minZ).setColor(127, 127, 127, 127).setUv(s.getU0(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.maxZ).setColor(127, 127, 127, 127).setUv(s.getU1(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.minX, (float) bb.minY, (float) bb.maxZ).setColor(127, 127, 127, 127).setUv(s.getU1(), s.getV0()).setLight(240);

            vb.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.maxZ).setColor(255, 255, 255, 127).setUv(s.getU0(), s.getV0()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.maxZ).setColor(255, 255, 255, 127).setUv(s.getU0(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.minZ).setColor(255, 255, 255, 127).setUv(s.getU1(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.minZ).setColor(255, 255, 255, 127).setUv(s.getU1(), s.getV0()).setLight(240);

            vb.addVertex((float) bb.minX, (float) bb.minY, (float) bb.maxZ).setColor(153, 153, 153, 127).setUv(s.getU0(), s.getV0()).setLight(240);
            vb.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.maxZ).setColor(153, 153, 153, 127).setUv(s.getU0(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.minZ).setColor(153, 153, 153, 127).setUv(s.getU1(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.minX, (float) bb.minY, (float) bb.minZ).setColor(153, 153, 153, 127).setUv(s.getU1(), s.getV0()).setLight(240);

            vb.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.minZ).setColor(153, 153, 153, 127).setUv(s.getU0(), s.getV0()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.minZ).setColor(153, 153, 153, 127).setUv(s.getU0(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.maxZ).setColor(153, 153, 153, 127).setUv(s.getU1(), s.getV1()).setLight(240);
            vb.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.maxZ).setColor(153, 153, 153, 127).setUv(s.getU1(), s.getV0()).setLight(240);
        }
//        Minecraft.getInstance().getProfiler().pop();

//        Minecraft.getInstance().getProfiler().pop();
    }
}
