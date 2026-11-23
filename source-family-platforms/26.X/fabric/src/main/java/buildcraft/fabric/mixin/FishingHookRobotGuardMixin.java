package buildcraft.fabric.mixin;

import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevent fishing hooks from catching robots without making robots unclickable or immune to other hits. */
@Mixin(FishingHook.class)
public abstract class FishingHookRobotGuardMixin {
    @Inject(method = "canHitEntity", at = @At("HEAD"), cancellable = true)
    private void bcce$ignoreRobots(Entity target, CallbackInfoReturnable<Boolean> cir) {
        if (target instanceof EntityRobot) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "onHitEntity", at = @At("HEAD"), cancellable = true)
    private void bcce$ignoreRobotHit(EntityHitResult hit, CallbackInfo ci) {
        if (hit.getEntity() instanceof EntityRobot) {
            ci.cancel();
        }
    }

    // FishingHook.retrieve() calls pullEntity() directly. Blocking only the
    // projectile collision is not sufficient to prevent the velocity impulse.
    @Inject(method = "pullEntity", at = @At("HEAD"), cancellable = true)
    private void bcce$ignoreRobotPull(Entity target, CallbackInfo ci) {
        if (target instanceof EntityRobot) {
            ci.cancel();
        }
    }
}
