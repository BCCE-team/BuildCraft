/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.fabric.mixin;

import buildcraft.fabric.PipePartClientInteraction;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
abstract class PipePartPickMixin {
    @Inject(method = "handlePickItemFromBlock", at = @At("HEAD"), cancellable = true)
    private void bcce$pickPipePart(BlockPos pos, boolean includeData, CallbackInfo ci) {
        if (PipePartClientInteraction.pick(pos, includeData)) ci.cancel();
    }
}
