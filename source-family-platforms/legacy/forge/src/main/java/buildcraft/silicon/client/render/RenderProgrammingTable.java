/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.render;

import javax.annotation.Nonnull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import buildcraft.silicon.tile.TileProgrammingTable_Neptune;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class RenderProgrammingTable implements BlockEntityRenderer<TileProgrammingTable_Neptune> {
	
	private static final ResourceLocation WHITE_STAINED_GLASS = new ResourceLocation("block/white_stained_glass");
	
	public RenderProgrammingTable(BlockEntityRendererProvider.Context bpc) {
	}
	
    @Override
    public void render(@Nonnull TileProgrammingTable_Neptune tile, float partialTicks, PoseStack matrix, MultiBufferSource buffer, int combinedLight, int overlay) {
        Minecraft.getInstance().getProfiler().push("bc");
        Minecraft.getInstance().getProfiler().push("table");
        Minecraft.getInstance().getProfiler().push("programming");

        int light1 = combinedLight >> 16 & 65535;
        int light2 = combinedLight & 65535;
        VertexConsumer bb = buffer.getBuffer(RenderType.translucent());
        TextureAtlasSprite whiteStainedGlass = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(WHITE_STAINED_GLASS);
        Matrix4f pose = matrix.last().pose();
        Matrix3f normal = matrix.last().normal();
        bb.vertex(pose, 4 / 16F, 9 / 16F, 4 / 16F).color(255, 255, 255, 255).uv(whiteStainedGlass.getU(4), whiteStainedGlass.getV(4)).overlayCoords(overlay).uv2(light1, light2).normal(normal, 0, 1, 0).endVertex();
        bb.vertex(pose, 4 / 16F, 9 / 16F, 12 / 16F).color(255, 255, 255, 255).uv(whiteStainedGlass.getU(4), whiteStainedGlass.getV(12)).overlayCoords(overlay).uv2(light1, light2).normal(normal, 0, 1, 0).endVertex();
        bb.vertex(pose, 12 / 16F, 9 / 16F, 12 / 16F).color(255, 255, 255, 255).uv(whiteStainedGlass.getU(12), whiteStainedGlass.getV(12)).overlayCoords(overlay).uv2(light1, light2).normal(normal, 0, 1, 0).endVertex();
        bb.vertex(pose, 12 / 16F, 9 / 16F, 4 / 16F).color(255, 255, 255, 255).uv(whiteStainedGlass.getU(12), whiteStainedGlass.getV(4)).overlayCoords(overlay).uv2(light1, light2).normal(normal, 0, 1, 0).endVertex();

        Minecraft.getInstance().getProfiler().pop();
        Minecraft.getInstance().getProfiler().pop();
        Minecraft.getInstance().getProfiler().pop();
    }
}
