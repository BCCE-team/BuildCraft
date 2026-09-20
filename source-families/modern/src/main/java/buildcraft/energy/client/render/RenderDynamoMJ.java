//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.energy.client.render;

import javax.annotation.Nullable;

import buildcraft.core.client.render.RenderEngine_BC8;
import buildcraft.energy.tile.TileDynamoMJ;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/**
 * 1.21.11 native MJ Dynamo renderer.
 *
 * <p>The Dynamo is a TileEngineBase_BC8 but needs the 12x12 moving head used by
 * its original BC8 model. The shared engine renderer already handles that from
 * the MJ_DYNAMO visual type, so this class only provides the correctly typed
 * BlockEntityRenderer registration wrapper.</p>
 */
public class RenderDynamoMJ implements BlockEntityRenderer<TileDynamoMJ, RenderEngine_BC8.EngineRenderState> {
    public RenderDynamoMJ(BlockEntityRendererProvider.Context context) {
    }

    public RenderEngine_BC8.EngineRenderState createRenderState() {
        return new RenderEngine_BC8.EngineRenderState();
    }

    public void extractRenderState(TileDynamoMJ tile, RenderEngine_BC8.EngineRenderState state, float partialTick,
        Vec3 cameraPosition, @Nullable ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
        RenderEngine_BC8.extractEngineState(tile, state, partialTick, crumblingOverlay);
    }

    public void submit(RenderEngine_BC8.EngineRenderState state, PoseStack poseStack, SubmitNodeCollector collector,
        CameraRenderState cameraState) {
        RenderEngine_BC8.submitEngine(state, poseStack, collector);
    }
}
