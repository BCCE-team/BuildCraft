/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.lib.client.render.laser;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Marks a legacy-style block-entity renderer that has BuildCraft laser geometry which can be submitted independently.
 *
 * <p>Minecraft 1.21.11 no longer invokes the legacy immediate {@code render(...)} method. Keeping the laser pass
 * separate lets the compatibility bridge submit only BuildCraft's laser geometry through the new render queue, without
 * accidentally replaying unrelated item/model rendering from the same block entity.</p>
 */
public interface LegacyLaserBlockEntityRenderer<T extends BlockEntity> {
    void renderLasers(T blockEntity, float partialTicks, PoseStack poseStack, MultiBufferSource bufferSource,
        int combinedLight, int combinedOverlay);
}
