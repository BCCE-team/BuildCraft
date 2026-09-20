/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.silicon.client.render;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import buildcraft.transport.internal.pluggable.IPlugDynamicRenderer;
import buildcraft.lib.client.model.MutableQuad;
import buildcraft.silicon.client.model.key.KeyPlugFacade;
import buildcraft.silicon.client.model.plug.PlugBakerFacade;
import buildcraft.silicon.plug.FacadePhasedState;
import buildcraft.silicon.plug.PluggableFacade;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders glass facades outside of the vanilla baked-block path.
 *
 * <p>Minecraft 1.21's block model renderer supplies its own alpha value while copying a {@code BakedQuad} into the
 * chunk buffer, so alpha stored in the quad vertex colour is ignored. Rendering the same facade geometry through a
 * {@link VertexConsumer} preserves the alpha already present in the glass texture.</p>
 */
public enum PlugFacadeRenderer implements IPlugDynamicRenderer<PluggableFacade> {
    INSTANCE;

    private static final int GLASS_ALPHA = 255;
    private static final Map<KeyPlugFacade, List<MutableQuad>> CACHE = new ConcurrentHashMap<>();

    public static void onModelBake() {
        CACHE.clear();
    }

    @Override
    public void render(PluggableFacade facade, float partialTicks, PoseStack poseStack, MultiBufferSource buffer,
        int combinedLight, int combinedOverlay) {
        FacadePhasedState facadeState = facade.states.phasedStates[facade.activeState];
        BlockState blockState = facadeState.stateInfo.state;
        if (!PluggableFacade.isGlass(blockState)) {
            return;
        }

        KeyPlugFacade key = new KeyPlugFacade(RenderType.translucent(), facade.side, blockState, facade.isHollow());
        List<MutableQuad> quads = CACHE.computeIfAbsent(
            key,
            modelKey -> List.copyOf(PlugBakerFacade.INSTANCE.bakeForKey(modelKey, false))
        );

        Matrix4f pose = poseStack.last().pose();
        Matrix3f normal = poseStack.last().normal();
        VertexConsumer consumer = buffer.getBuffer(RenderType.translucent());

        for (MutableQuad source : quads) {
            MutableQuad quad = new MutableQuad(source);
            int tintIndex = source.getTint();
            if (tintIndex != -1) {
                int blockTint = facade.getBlockColor(tintIndex / Direction.values().length);
                if (blockTint != -1) {
                    quad.multColouri(
                        (blockTint >> 16) & 0xFF,
                        (blockTint >> 8) & 0xFF,
                        blockTint & 0xFF,
                        GLASS_ALPHA
                    );
                } else {
                    quad.multColouri(255, 255, 255, GLASS_ALPHA);
                }
            } else {
                quad.multColouri(255, 255, 255, GLASS_ALPHA);
            }
            quad.lighti(combinedLight);
            for (var vertex : quad.vertexs) {
                vertex.overlay(combinedOverlay);
            }
            quad.multShade();
            quad.render(pose, normal, consumer);
        }
    }
}
