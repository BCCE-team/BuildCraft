package buildcraft.energy.fluid;

import net.minecraft.resources.ResourceLocation;
//? if <1.21.9 {
import java.util.function.Consumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import org.joml.Vector3f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
//?} else if mc_26_x {
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
//?}
import net.neoforged.neoforge.fluids.FluidType;

public class BCFluidType extends FluidType{

    //store fluid type, but no temperature
    private final ResourceLocation stillTexture;
    private final ResourceLocation flowTexture;
    private final int tintColor;

    public BCFluidType(Properties properties, ResourceLocation stillTexture, ResourceLocation flowTexture, int tintColor) {
        super(properties);
        this.stillTexture = stillTexture;
        this.flowTexture = flowTexture;
        this.tintColor = tintColor;
    }

    public ResourceLocation getStillTextureLocation() {
        return stillTexture;
    }

    public ResourceLocation getFlowTextureLocation() {
        return flowTexture;
    }

    public int getFluidTintColor() {
        return tintColor;
    }

    //? if mc_26_x {
    /**
     * NeoForge 26.x no longer gives non-waterlike custom fluids the vanilla water travel fallback.
     * BuildCraft fluids historically used that fallback, while sticky oil applies its additional slowdown
     * through {@link BCLiquidBlock#entityInside}. Reproduce the vanilla water-travel step here without
     * marking oil as water-like (which would incorrectly change drowning, swimming, mining, and AI rules).
     */
    @Override
    public boolean move(LivingEntity entity, Vec3 movementVector, double gravity) {
        boolean isFalling = entity.getDeltaMovement().y <= 0.0D;
        double oldY = entity.getY();

        float slowDown = entity.isSprinting() ? 0.9F : 0.8F;
        float speed = 0.02F;
        float waterMovementEfficiency = (float) entity.getAttributeValue(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (!entity.onGround()) {
            waterMovementEfficiency *= 0.5F;
        }
        if (waterMovementEfficiency > 0.0F) {
            slowDown += (0.54600006F - slowDown) * waterMovementEfficiency;
            speed += (entity.getSpeed() - speed) * waterMovementEfficiency;
        }
        if (entity.hasEffect(MobEffects.DOLPHINS_GRACE)) {
            slowDown = 0.96F;
        }

        speed *= (float) entity.getAttributeValue(NeoForgeMod.SWIM_SPEED);
        entity.moveRelative(speed, movementVector);
        entity.move(MoverType.SELF, entity.getDeltaMovement());

        Vec3 movement = entity.getDeltaMovement();
        if (entity.horizontalCollision && entity.onClimbable()) {
            movement = new Vec3(movement.x, 0.2D, movement.z);
        }
        movement = movement.multiply(slowDown, 0.8F, slowDown);
        entity.setDeltaMovement(entity.getFluidFallingAdjustedMovement(gravity, isFalling, movement));

        movement = entity.getDeltaMovement();
        if (entity.horizontalCollision
            && entity.isFree(movement.x, movement.y + 0.6F - entity.getY() + oldY, movement.z)) {
            entity.setDeltaMovement(movement.x, 0.3F, movement.z);
        }
        return true;
    }
    //?}

    //? if <1.21.9 {
    @Override
    public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer)
    {
        consumer.accept(new IClientFluidTypeExtensions() {
           ResourceLocation UNDERWATER_LOCATION =
               ResourceLocation.withDefaultNamespace("textures/misc/underwater.png");

            @Override
            public ResourceLocation getStillTexture() {
                return stillTexture;
            }

            @Override
            public ResourceLocation getFlowingTexture() {
                return flowTexture;
            }

            @Override
            public @Nullable ResourceLocation getOverlayTexture() {
                // This texture replaces side faces when a fluid touches a block. Using the vanilla water
                // overlay here turns every BuildCraft fluid side into a white/grey water-overlay quad.
                return null;
            }

            @Override
            public ResourceLocation getRenderOverlayTexture(Minecraft mc)
            {
                return UNDERWATER_LOCATION;
            }

            @Override
            public int getTintColor() {
                return tintColor;
            }

            @Override
            public @NotNull Vector3f modifyFogColor(Camera camera, float partialTick, ClientLevel level,
                    int renderDistance, float darkenWorldAmount, Vector3f fluidFogColor) {
                //afluidFogColor.mul(((tintColor>>16)&0xff)/255f, ((tintColor>>8)&0xff)/255f, ((tintColor)&0xff)/255f);
                return new Vector3f(0.5f, 0.5f, 0.5f);
            }




        });
    }
    //?}



}
