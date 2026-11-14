package buildcraft.fabric.mixin;

import buildcraft.lib.platform.events.BCEvents;
import buildcraft.lib.platform.events.PlatformEvents;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Matches native client player tick boundaries and avoids extra ticks during pause. */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void bcce$start(CallbackInfo callback) {
        PlatformEvents.fireClientPlayerTick((LocalPlayer) (Object) this, BCEvents.Phase.START);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void bcce$end(CallbackInfo callback) {
        PlatformEvents.fireClientPlayerTick((LocalPlayer) (Object) this, BCEvents.Phase.END);
    }
}
