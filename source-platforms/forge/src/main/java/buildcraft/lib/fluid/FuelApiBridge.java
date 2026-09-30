package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidComponentPayload;
import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.internal.data.NbtSquishConstants;
import buildcraft.lib.nbt.NbtSquisher;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
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
    private static final ResourceLocation COMPONENT_FORMAT = new ResourceLocation("buildcraftlib", "forge_fluid_nbt");

    public static final FluidMatchContext MATCH_CONTEXT = FuelApiBridge::isInTag;

    private FuelApiBridge() {}

    public static FluidVariant variantOf(FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getFluid() == Fluids.EMPTY) {
            throw new IllegalArgumentException("Cannot create API fluid variant from an empty FluidStack");
        }
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        if (id == null) throw new IllegalArgumentException("Unregistered fluid: " + stack.getFluid());
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return FluidVariant.of(id);
        }
        byte[] componentBytes = NbtSquisher.squish(tag, NbtSquishConstants.VANILLA);
        return FluidVariant.of(id, FluidComponentPayload.of(COMPONENT_FORMAT, componentBytes));
    }

    public static FluidVolume volumeOf(FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getAmount() <= 0) return FluidVolume.empty();
        return FluidVolume.of(variantOf(stack), FluidAmount.of(stack.getAmount()));
    }

    private static FluidStack stackOfVariantWithComponents(FluidVariant variant, int amount) {
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(variant.fluidId());
        if (fluid == null || fluid == Fluids.EMPTY) return FluidStack.EMPTY;

        FluidStack stack = new FluidStack(fluid, amount);
        FluidComponentPayload components = variant.components();
        if (components.isEmpty() || !components.formatId().filter(COMPONENT_FORMAT::equals).isPresent()) {
            return stack;
        }
        try {
            CompoundTag tag = NbtSquisher.expand(components.copyCanonicalBytes());
            if (!tag.isEmpty()) {
                stack.setTag(tag);
            }
        } catch (IOException | RuntimeException ignored) {
            // Malformed or foreign component payloads degrade to the plain fluid stack.
        }
        return stack;
    }

    public static FluidStack stackOf(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidStack.EMPTY;
        long amount = volume.amount().milliBuckets();
        if (amount > Integer.MAX_VALUE) {
            throw new ArithmeticException("Legacy FluidStack cannot represent " + amount + " mB");
        }
        return stackOfVariantWithComponents(volume.requireVariant(), (int) amount);
    }

    public static FluidStack stackOfVariant(FluidVariant variant, int amount) {
        Objects.requireNonNull(variant, "variant");
        return stackOfVariantWithComponents(variant, amount);
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
