//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import buildcraft.lib.client.render.DetachedRenderer.IDetachedRenderer;
import net.minecraft.world.entity.player.Player;

public enum MarkerRenderer implements IDetachedRenderer {
    INSTANCE;

    public void render(PoseStack pose, Matrix4f matrix, Player player, float partialTicks) {
        // 1.21.11 world marker geometry is submitted through SubmitCustomGeometryEvent by Core.
    }
}
