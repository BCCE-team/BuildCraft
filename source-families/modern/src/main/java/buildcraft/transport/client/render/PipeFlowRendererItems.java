//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.client.render;

import java.util.List;

import buildcraft.lib.client.model.ModelUtil;
import buildcraft.lib.client.model.ModelUtil.UvFaceData;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.internal.core.render.ISprite;
import buildcraft.lib.misc.ColourUtil;
import buildcraft.transport.BCTransportSprites;
import buildcraft.transport.internal.pipe.IPipeFlowRenderer;
import buildcraft.transport.pipe.flow.PipeFlowItems;
import buildcraft.transport.pipe.flow.TravellingItem;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.joml.Vector3f;

/** 1.21.11 travelling-item renderer using the native item submission API. */
public enum PipeFlowRendererItems implements IPipeFlowRenderer<PipeFlowItems> {
    INSTANCE;

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

    /** The direct-render entry point is intentionally unused by the 1.21.11 BER. */
    public void render(PipeFlowItems flow, float partialTicks, PoseStack matrix, MultiBufferSource buffer,
        int light, int combinedOverlay) {
    }

    public void submit(PipeFlowItems flow, float partialTicks, PoseStack matrix, SubmitNodeCollector collector,
        int light, int overlay) {
        Level world = flow.pipe.getHolder().getPipeWorld();
        long now = world.getGameTime();
        List<TravellingItem> toRender = flow.getAllItemsForRender();

        matrix.pushPose();
        matrix.translate(0.5f, 0.5f, 0.5f);
        try {
            for (TravellingItem item : toRender) {
                Vec3 pos = item.getRenderPosition(BlockPos.ZERO, now, partialTicks, flow);
                ItemStack stack = item.clientItemLink.get();

                if (!stack.isEmpty()) {
                    matrix.pushPose();
                    matrix.translate(pos.x, -0.2f + pos.y, pos.z);
                    try {
                        ItemStackRenderState itemState = new ItemStackRenderState();
                        Minecraft.getInstance().getItemModelResolver().updateForTopItem(
                            itemState, stack, ItemDisplayContext.GROUND, world, null, 0
                        );
                        itemState.submit(matrix, collector, light, overlay, 0);
                    } finally {
                        matrix.popPose();
                    }
                }

                if (item.colour != null) {
                    submitColourBox(item, pos, matrix, collector, light, overlay);
                }
            }
        } finally {
            matrix.popPose();
        }
    }

    private static void submitColourBox(TravellingItem item, Vec3 pos, PoseStack matrix,
        SubmitNodeCollector collector, int light, int overlay) {
        if (COLOURED_QUADS[0] == null) {
            onModelBake();
        }

        int col = ColourUtil.getLightHex(item.colour);
        int r = (col >> 16) & 0xFF;
        int g = (col >> 8) & 0xFF;
        int b = col & 0xFF;

        matrix.pushPose();
        matrix.translate(pos.x, pos.y, pos.z);
        try {
            collector.submitCustomGeometry(matrix, RenderCompat.cutout(), (pose, consumer) -> {
                for (MutableQuad source : COLOURED_QUADS) {
                    if (source == null) {
                        continue;
                    }
                    MutableQuad quad = new MutableQuad(source);
                    quad.lighti(light);
                    quad.multColouri(r, g, b, 255);
                    for (var vertex : quad.vertexs) {
                        vertex.overlay(overlay);
                    }
                    quad.render(pose.pose(), pose.normal(), consumer);
                }
            });
        } finally {
            matrix.popPose();
        }
    }
}
