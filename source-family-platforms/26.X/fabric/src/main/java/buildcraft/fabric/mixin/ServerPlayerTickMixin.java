package buildcraft.fabric.mixin;

import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Matches per-player BEFORE/AFTER tick timing, including during low TPS. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void bcce$startTick(CallbackInfo ci) {
        PlatformEvents.fireServerPlayerTick((ServerPlayer) (Object) this, BCEvents.Phase.START);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void bcce$endTick(CallbackInfo ci) {
        PlatformEvents.fireServerPlayerTick((ServerPlayer) (Object) this, BCEvents.Phase.END);
    }
}
