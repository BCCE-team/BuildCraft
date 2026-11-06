//? source if >=26.1
/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.render.pip;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.world.level.material.FluidState;

/** NeoForge 26.x: the baked FluidModel, not IClientFluidTypeExtensions, owns fluid tint. */
final class PreviewFluidTint {
    private PreviewFluidTint() {}

    static int color(FluidState state) {
        if (state == null || state.isEmpty()) return 0xFFFFFFFF;
        try {
            FluidModel model = Minecraft.getInstance().getModelManager()
                .getFluidStateModelSet().get(state);
            var tint = model.fluidTintSource();
            return tint == null ? 0xFFFFFFFF : tint.color(state);
        } catch (RuntimeException unavailableDuringReload) {
            // Do not crash a blueprint GUI while the resource/model manager reloads.
            return 0xFFFFFFFF;
        }
    }
}
