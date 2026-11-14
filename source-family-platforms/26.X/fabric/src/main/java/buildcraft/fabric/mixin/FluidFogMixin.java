package buildcraft.fabric.mixin;

import buildcraft.energy.fluid.BCFluidFog;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.fluid.FabricFluidProperties;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Fluid-specific immersion fog for BuildCraft oil, fuel and heated variants. */
@Mixin(FogRenderer.class)
public abstract class FluidFogMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private void bcce$fluidFog(Camera camera, int distance, DeltaTracker tracker,
        float darkenWorldAmount, ClientLevel level, CallbackInfoReturnable<FogData> result) {
        if (camera == null || level == null) return;
        BlockPos pos = BlockPos.containing(camera.position());
        FluidState state = level.getFluidState(pos);
        if (state.isEmpty() || !(state.getType() instanceof BCFluid fluid)) return;
        if (camera.position().y() >= pos.getY() + state.getHeight(level, pos)) return;
        FogData fog = result.getReturnValue();
        if (fog == null) return;
        int color = BCFluidFog.color(fluid.getLightColour(), fluid.getDarkColour(), fluid.getHeatValue());
        fog.color.set(BCFluidFog.channel(color, 16), BCFluidFog.channel(color, 8),
            BCFluidFog.channel(color, 0), 1.0F);
        fog.environmentalStart = 0.0F;
        fog.environmentalEnd = BCFluidFog.distance(FabricFluidProperties.density(fluid));
        fog.skyEnd = fog.environmentalEnd;
        fog.cloudEnd = fog.environmentalEnd;
    }
}
