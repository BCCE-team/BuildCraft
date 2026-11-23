package buildcraft.robotics.mixin;

import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FishingHook does not go through LivingEntity knockback when reeled in: it
 * calls pullEntity and writes directly to the target's velocity. Intercept
 * both attachment and the pull, so the robot's AI movement remains untouched.
 */
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

    @Inject(method = "pullEntity", at = @At("HEAD"), cancellable = true)
    private void bcce$ignoreRobotPull(Entity target, CallbackInfo ci) {
        if (target instanceof EntityRobot) {
            ci.cancel();
        }
    }
}
