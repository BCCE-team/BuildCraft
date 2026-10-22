//? source if >=26.2
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.compat.minecraft.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.rendertype.RenderType;

/** A layer-addressed vertex sink used only while extracting immutable render data. */
@FunctionalInterface
public interface BCVertexBuffers {
    VertexConsumer getBuffer(RenderType type);
}
