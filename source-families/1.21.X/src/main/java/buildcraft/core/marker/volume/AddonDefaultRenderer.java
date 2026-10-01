/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.marker.volume;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.phys.AABB;
public class AddonDefaultRenderer<T extends Addon> implements IFastAddonRenderer<T> {
    private final TextureAtlasSprite s;

    public AddonDefaultRenderer() {
        s = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(ResourceLocation.fromNamespaceAndPath("minecraft", "block/quartz_block_top")); // Quartz top is the neutral white fallback sprite for debug geometry.
    }

    public AddonDefaultRenderer(TextureAtlasSprite s) {
        this.s = s;
    }

    @Override
    public void renderAddonFast(T addon, Player player, float partialTicks, VertexConsumer builder) {
        AABB bb = addon.getBoundingBox();

        builder.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.minZ).setColor(204, 204, 204, 255).setUv(s.getU0(), s.getV0()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.minZ).setColor(204, 204, 204, 255).setUv(s.getU0(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.minZ).setColor(204, 204, 204, 255).setUv(s.getU1(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.minX, (float) bb.minY, (float) bb.minZ).setColor(204, 204, 204, 255).setUv(s.getU1(), s.getV0()).setLight(240);

        builder.addVertex((float) bb.minX, (float) bb.minY, (float) bb.maxZ).setColor(204, 204, 204, 255).setUv(s.getU0(), s.getV0()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.maxZ).setColor(204, 204, 204, 255).setUv(s.getU0(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.maxZ).setColor(204, 204, 204, 255).setUv(s.getU1(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.maxZ).setColor(204, 204, 204, 255).setUv(s.getU1(), s.getV0()).setLight(240);

        builder.addVertex((float) bb.minX, (float) bb.minY, (float) bb.minZ).setColor(127, 127, 127, 255).setUv(s.getU0(), s.getV0()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.minZ).setColor(127, 127, 127, 255).setUv(s.getU0(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.maxZ).setColor(127, 127, 127, 255).setUv(s.getU1(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.minX, (float) bb.minY, (float) bb.maxZ).setColor(127, 127, 127, 255).setUv(s.getU1(), s.getV0()).setLight(240);

        builder.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.maxZ).setColor(255, 255, 255, 255).setUv(s.getU0(), s.getV0()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.maxZ).setColor(255, 255, 255, 255).setUv(s.getU0(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.minZ).setColor(255, 255, 255, 255).setUv(s.getU1(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.minZ).setColor(255, 255, 255, 255).setUv(s.getU1(), s.getV0()).setLight(240);

        builder.addVertex((float) bb.minX, (float) bb.minY, (float) bb.maxZ).setColor(153, 153, 153, 255).setUv(s.getU0(), s.getV0()).setLight(240);
        builder.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.maxZ).setColor(153, 153, 153, 255).setUv(s.getU0(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.minX, (float) bb.maxY, (float) bb.minZ).setColor(153, 153, 153, 255).setUv(s.getU1(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.minX, (float) bb.minY, (float) bb.minZ).setColor(153, 153, 153, 255).setUv(s.getU1(), s.getV0()).setLight(240);

        builder.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.minZ).setColor(153, 153, 153, 255).setUv(s.getU0(), s.getV0()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.minZ).setColor(153, 153, 153, 255).setUv(s.getU0(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.maxY, (float) bb.maxZ).setColor(153, 153, 153, 255).setUv(s.getU1(), s.getV1()).setLight(240);
        builder.addVertex((float) bb.maxX, (float) bb.minY, (float) bb.maxZ).setColor(153, 153, 153, 255).setUv(s.getU1(), s.getV0()).setLight(240);
    }
}
