/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
//? if <1.20 {
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
//?} else {
/*?
import org.joml.Matrix3f;
import org.joml.Matrix4f;
?*/
//?}

import buildcraft.transport.internal.pluggable.IPlugDynamicRenderer;
import buildcraft.lib.client.model.AdvModelCache;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.silicon.BCSiliconModels;
import buildcraft.silicon.plug.PluggablePulsar;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
public enum PlugPulsarRenderer implements IPlugDynamicRenderer<PluggablePulsar> {
    INSTANCE;

    private static final AdvModelCache cache =
        new AdvModelCache(BCSiliconModels.PULSAR_DYNAMIC, PluggablePulsar.MODEL_VAR_INFO);

    public static void onModelBake() {
        cache.reset();
    }

    @Override
    public void render(PluggablePulsar pulsar, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,int combinedLight, int combinedOverlay) {
        if (pulsar.clientModelData.hasNoNodes()) {
            pulsar.clientModelData.setNodes(BCSiliconModels.PULSAR_DYNAMIC.createTickableNodes());
        }
        pulsar.setModelVariables(partialTicks);
        pulsar.clientModelData.refresh();
        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        VertexConsumer bb = buffer.getBuffer(RenderType.cutout());
        for (MutableQuad q : cache.getCutoutQuads()) {
            q.render(pose, normal, bb);
        }
    }
}
