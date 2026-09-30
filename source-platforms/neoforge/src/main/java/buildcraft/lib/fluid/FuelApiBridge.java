package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidComponentPayload;
import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.internal.data.NbtSquishConstants;
import buildcraft.lib.misc.FluidStackUtil;
import buildcraft.lib.nbt.NbtSquisher;
import java.io.IOException;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** NeoForge bridge for the loader-neutral API 2 fuel/coolant domain. */
public final class FuelApiBridge {
    private static final ResourceLocation COMPONENT_FORMAT =
            ResourceLocation.fromNamespaceAndPath("buildcraftlib", "neoforge_fluid_components");

    public static final FluidMatchContext MATCH_CONTEXT = FuelApiBridge::isInTag;

    private FuelApiBridge() {}

    public static FluidVariant variantOf(FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getFluid() == Fluids.EMPTY) {
            throw new IllegalArgumentException("Cannot create API fluid variant from an empty FluidStack");
        }

        ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered fluid: " + stack.getFluid());
        }

        CompoundTag serialized = FluidStackUtil.saveOptional(stack);
        if (!serialized.contains("components", Tag.TAG_COMPOUND)) {
            return FluidVariant.of(id);
        }

        CompoundTag components = serialized.getCompound("components");
        if (components.isEmpty()) {
            return FluidVariant.of(id);
        }

        byte[] componentBytes = NbtSquisher.squish(components, NbtSquishConstants.VANILLA);
        return FluidVariant.of(id, FluidComponentPayload.of(COMPONENT_FORMAT, componentBytes));
    }

    public static FluidVolume volumeOf(FluidStack stack) {
        if (stack == null || stack.isEmpty() || stack.getAmount() <= 0) return FluidVolume.empty();
        return FluidVolume.of(variantOf(stack), FluidAmount.of(stack.getAmount()));
    }
    private static FluidStack stackOfVariantWithComponents(FluidVariant variant, int amount) {
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(variant.fluidId()).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }

        FluidComponentPayload components = variant.components();
        if (components.isEmpty() || !components.formatId().filter(COMPONENT_FORMAT::equals).isPresent()) {
            return new FluidStack(fluid, amount);
        }

        try {
            CompoundTag componentTag = NbtSquisher.expand(components.copyCanonicalBytes());

            CompoundTag serialized = new CompoundTag();
            serialized.putString("id", variant.fluidId().toString());
            serialized.putInt("amount", amount);
            serialized.put("components", componentTag);

            return FluidStackUtil.parseOptional(serialized);
        } catch (IOException | RuntimeException e) {
            return new FluidStack(fluid, amount);
        }
    }
    public static FluidStack stackOf(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidStack.EMPTY;
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(volume.requireVariant().fluidId()).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) return FluidStack.EMPTY;
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
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(fluidId).orElse(Fluids.EMPTY);
        return fluid != Fluids.EMPTY && fluid.is(TagKey.create(Registries.FLUID, tagId));
    }
}
