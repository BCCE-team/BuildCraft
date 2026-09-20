/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.client.render;

import java.util.List;

import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.transport.internal.pipe.IPipeFlowRenderer;
import buildcraft.lib.client.model.ModelUtil;
import buildcraft.lib.client.model.ModelUtil.UvFaceData;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.misc.ColourUtil;
import buildcraft.transport.BCTransportSprites;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.pipe.flow.TravellingItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <1.20 {
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
//?} else {
/*?
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
?*/
//?}

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
//? if <1.20 {
import net.minecraft.client.renderer.block.model.ItemTransforms.TransformType;
//?} else {
/*?
import net.minecraft.world.item.ItemDisplayContext;
?*/
//?}
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
public enum PipeFlowRendererItems implements IPipeFlowRenderer<PipeFlowItems> {
	INSTANCE;
	
	private static ItemRenderer itemRender;
	
    private static final MutableQuad[] COLOURED_QUADS = new MutableQuad[6];

    public static void onModelBake() {
        Vector3f center = new Vector3f();
        Vector3f radius = new Vector3f(0.2f, 0.2f, 0.2f);

        ISprite sprite = BCTransportSprites.COLOUR_ITEM_BOX;
        UvFaceData uvs = new UvFaceData();
        uvs.minU = (float) sprite.getInterpU(0);
        uvs.maxU = (float) sprite.getInterpU(1);
        uvs.minV = (float) sprite.getInterpV(0);
        uvs.maxV = (float) sprite.getInterpV(1);

        for (Direction face : Direction.values()) {
            MutableQuad q = ModelUtil.createFace(face, center, radius, uvs);
            q.setCalculatedDiffuse();
            COLOURED_QUADS[face.ordinal()] = q;
        }
    }

    @Override
    public void render(PipeFlowItems flow, float partialTicks, PoseStack matrix, MultiBufferSource buffer,
			int lightc, int combinedOverlay) {
    	if(itemRender == null) itemRender = Minecraft.getInstance().getItemRenderer();
        Level world = flow.pipe.getHolder().getPipeWorld();
        long now = world.getGameTime();
        List<TravellingItem> toRender = flow.getAllItemsForRender();
        
        matrix.translate(0.5f, 0.5f, 0.5f);
        for (TravellingItem item : toRender) {
            Vec3 pos = item.getRenderPosition(BlockPos.ZERO, now, partialTicks, flow);
            
            ItemStack stack = item.clientItemLink.get();
            
            if (!stack.isEmpty()) {
            	matrix.translate(pos.x, -0.2f+pos.y, pos.z);
            	//? if <1.20 {
            	itemRender.renderStatic(stack, TransformType.GROUND, lightc, combinedOverlay, matrix, buffer, 0);
            	//?} else {
            	/*?
            	itemRender.renderStatic(stack, ItemDisplayContext.GROUND, lightc, combinedOverlay, matrix, buffer, world, 0);
            	?*/
            	//?}
                matrix.translate(-pos.x, 0.2f-pos.y, -pos.z);
            }
            if (item.colour != null) {
                matrix.translate(pos.x, pos.y, pos.z);
                Matrix4f pose = matrix.last().pose();
                Matrix3f normal = matrix.last().normal();
                VertexConsumer builder = buffer.getBuffer(RenderType.cutoutMipped());
                int col = ColourUtil.getLightHex(item.colour);
                int r = (col >> 16) & 0xFF;
                int g = (col >> 8) & 0xFF;
                int b = col & 0xFF;
                for (MutableQuad q : COLOURED_QUADS) {
                    MutableQuad q2 = new MutableQuad(q);
                    q2.lighti(lightc);
                    q2.multColouri(r, g, b, 255);
                    q2.render(pose, normal, builder);
                }
                matrix.translate(-pos.x, -pos.y, -pos.z);
            }
        }

    }
}
