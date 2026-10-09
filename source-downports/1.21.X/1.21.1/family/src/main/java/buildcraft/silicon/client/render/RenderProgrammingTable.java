/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.render;

import buildcraft.lib.compat.minecraft.render.BCRenderTypes;
import buildcraft.lib.compat.minecraft.render.BCGeometryRenderer;
import javax.annotation.Nonnull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.silicon.tile.TileProgrammingTable_Neptune;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
public class RenderProgrammingTable implements BCGeometryRenderer<TileProgrammingTable_Neptune> {
	
	private static final ResourceLocation WHITE_STAINED_GLASS = ResourceLocation.withDefaultNamespace("block/white_stained_glass");
	
	public RenderProgrammingTable(BlockEntityRendererProvider.Context bpc) {
	}
	
    @Override
    public void renderContents(@Nonnull TileProgrammingTable_Neptune tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int combinedLight, int overlay) {
        Minecraft.getInstance().getProfiler().push("bc");
        Minecraft.getInstance().getProfiler().push("table");
        Minecraft.getInstance().getProfiler().push("programming");

        VertexConsumer bb = buffer.getBuffer(BCRenderTypes.translucent());
        TextureAtlasSprite whiteStainedGlass = Minecraft.getInstance()
            .getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WHITE_STAINED_GLASS);
        PoseStack.Pose pose = matrix.last();
        bb.addVertex(pose.pose(), 4 / 16F, 9 / 16F, 4 / 16F).setColor(255, 255, 255, 255).setUv(whiteStainedGlass.getU(4 / 16.0F), whiteStainedGlass.getV(4 / 16.0F)).setOverlay(overlay).setLight(combinedLight).setNormal(pose, 0, 1, 0);
        bb.addVertex(pose.pose(), 4 / 16F, 9 / 16F, 12 / 16F).setColor(255, 255, 255, 255).setUv(whiteStainedGlass.getU(4 / 16.0F), whiteStainedGlass.getV(12 / 16.0F)).setOverlay(overlay).setLight(combinedLight).setNormal(pose, 0, 1, 0);
        bb.addVertex(pose.pose(), 12 / 16F, 9 / 16F, 12 / 16F).setColor(255, 255, 255, 255).setUv(whiteStainedGlass.getU(12 / 16.0F), whiteStainedGlass.getV(12 / 16.0F)).setOverlay(overlay).setLight(combinedLight).setNormal(pose, 0, 1, 0);
        bb.addVertex(pose.pose(), 12 / 16F, 9 / 16F, 4 / 16F).setColor(255, 255, 255, 255).setUv(whiteStainedGlass.getU(12 / 16.0F), whiteStainedGlass.getV(4 / 16.0F)).setOverlay(overlay).setLight(combinedLight).setNormal(pose, 0, 1, 0);

        Minecraft.getInstance().getProfiler().pop();
        Minecraft.getInstance().getProfiler().pop();
        Minecraft.getInstance().getProfiler().pop();
    }
}
