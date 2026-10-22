/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import buildcraft.transport.internal.pluggable.IPlugDynamicRenderer;
import buildcraft.lib.client.model.AdvModelCache;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.plug.PluggableGate;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
public enum PlugGateRenderer implements IPlugDynamicRenderer<PluggableGate> {
    INSTANCE;

    private static final AdvModelCache cache = new AdvModelCache(BCSiliconModels.GATE_DYNAMIC, PluggableGate.MODEL_VAR_INFO);

    public static void onModelBake() {
        cache.reset();
    }

    @Override
    public void render(PluggableGate gate, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,int combinedLight, int combinedOverlay) {
        gate.setClientModelVariables();
        if (gate.clientModelData.hasNoNodes()) {
            gate.clientModelData.setNodes(BCSiliconModels.GATE_DYNAMIC.createTickableNodes());
        }
        gate.clientModelData.refresh();
        MutableQuad copy = new MutableQuad();
        Pose last = poseStack.last();
        Matrix4f pose = last.pose();
        Matrix3f normal = last.normal();
        VertexConsumer bb = buffer.getBuffer(RenderType.cutout());
        for (MutableQuad q : cache.getCutoutQuads()) {
            copy.copyFrom(q);
            copy.multShade();
            copy.render(pose, normal, bb);
        }
    }
}
