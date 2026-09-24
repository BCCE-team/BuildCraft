package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.internal.transfer.FluidCarrier;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
//? if <1.20 {
import net.minecraft.core.Registry;
//?} else {
/*?
import net.minecraft.core.registries.Registries;
?*/
//?}

/** Forge bridge for the loader-neutral API 2 fuel/coolant domain. */
public final class FuelApiBridge {
    public static final FluidMatchContext MATCH_CONTEXT = FuelApiBridge::isInTag;
    public static final FluidCarrier<FluidStack> CARRIER = new FluidCarrier<>() {
        @Override public boolean isEmpty(FluidStack stack) { return stack == null || stack.isEmpty(); }
        @Override public int amount(FluidStack stack) { return stack == null ? 0 : stack.getAmount(); }
        @Override public FluidVolume toVolume(FluidStack stack) { return volumeOf(stack); }
        @Override public FluidStack fromVolume(FluidVolume volume) { return stackOf(volume); }
        @Override public FluidStack copyWithAmount(FluidStack stack, int amount) {
            FluidStack copy = stack.copy();
            copy.setAmount(amount);
            return copy;
        }
    };

    private FuelApiBridge() {}

    public static FluidVariant variantOf(FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getFluid() == Fluids.EMPTY) {
            throw new IllegalArgumentException("Cannot create API fluid variant from an empty FluidStack");
        }
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        if (id == null) throw new IllegalArgumentException("Unregistered fluid: " + stack.getFluid());
        // Fuel/coolant matching is registry/tag based. Platform component payload
        // preservation remains the responsibility of the general FluidService bridge.
        return FluidVariant.of(id);
    }

    public static FluidVolume volumeOf(FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getAmount() <= 0) return FluidVolume.empty();
        return FluidVolume.of(variantOf(stack), FluidAmount.of(stack.getAmount()));
    }

    public static FluidStack stackOf(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidStack.EMPTY;
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(volume.requireVariant().fluidId());
        if (fluid == null || fluid == Fluids.EMPTY) return FluidStack.EMPTY;
        long amount = volume.amount().milliBuckets();
        if (amount > Integer.MAX_VALUE) {
            throw new ArithmeticException("Legacy FluidStack cannot represent " + amount + " mB");
        }
        return new FluidStack(fluid, (int) amount);
    }

    public static FluidStack stackOfVariant(FluidVariant variant, int amount) {
        Objects.requireNonNull(variant, "variant");
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(variant.fluidId());
        return fluid == null || fluid == Fluids.EMPTY ? FluidStack.EMPTY : new FluidStack(fluid, amount);
    }

    public static boolean equivalentTo(FluidStack template, FluidVariant candidate) {
        if (template == null || template.isEmpty() || candidate == null) return false;
        FluidStack candidateStack = stackOfVariant(candidate, Math.max(1, template.getAmount()));
        return !candidateStack.isEmpty() && FluidCompatRegistry.areEquivalent(template, candidateStack);
    }

    private static boolean isInTag(ResourceLocation fluidId, ResourceLocation tagId) {
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(fluidId);
        if (fluid == null || fluid == Fluids.EMPTY) return false;
        TagKey<Fluid> tag = TagKey.create(
            //? if <1.20 {
            Registry.FLUID_REGISTRY,
            //?} else {
            /*?
            Registries.FLUID,
            ?*/
            //?}
            tagId
        );
        return fluid.is(tag);
    }
}
