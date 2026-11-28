package buildcraft.fabric.mixin;

import buildcraft.energy.fluid.BCFluidFog;
import buildcraft.lib.fluid.BCFluid;
import buildcraft.lib.fluid.FabricFluidProperties;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Apply petroleum fog before vanilla uploads its 1.21.11 fog uniform buffer. */
@Mixin(FogRenderer.class)
public abstract class FluidFogMixin {
    @ModifyArgs(method = "setupFog", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void bcce$fluidFog(Args args, Camera camera, int distance, DeltaTracker tracker,
        float darkenWorldAmount, ClientLevel level) {
        if (camera == null || level == null) return;
        BlockPos pos = BlockPos.containing(camera.position());
        FluidState state = level.getFluidState(pos);
        if (state.isEmpty() || !(state.getType() instanceof BCFluid fluid)) return;
        if (camera.position().y() >= pos.getY() + state.getHeight(level, pos)) return;
        int color = BCFluidFog.color(fluid.getLightColour(), fluid.getDarkColour(), fluid.getHeatValue());
        Vector4f fogColor = args.get(2);
        fogColor.set(BCFluidFog.channel(color, 16), BCFluidFog.channel(color, 8),
            BCFluidFog.channel(color, 0), 1.0F);
        float end = BCFluidFog.distance(FabricFluidProperties.density(fluid));
        args.set(3, 0.0F); // environmentalStart
        args.set(4, end);  // environmentalEnd
        args.set(7, end);  // skyEnd
        args.set(8, end);  // cloudEnd
    }
}
