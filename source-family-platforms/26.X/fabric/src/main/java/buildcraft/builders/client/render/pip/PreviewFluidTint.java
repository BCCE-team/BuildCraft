/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.render.pip;

import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.world.level.material.FluidState;

/** NeoForge 26.x: the baked FluidModel, not IClientFluidTypeExtensions, owns fluid tint. */
final class PreviewFluidTint {
    private PreviewFluidTint() {}

    static int color(FluidState state) {
        if (state == null || state.isEmpty()) return 0xFFFFFFFF;
        try {
            return FluidVariantRendering.getColor(FluidVariant.of(state.getType()));
        } catch (RuntimeException unavailableDuringReload) {
            return 0xFFFFFFFF;
        }
    }
}
