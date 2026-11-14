package buildcraft.lib.compat;

import buildcraft.lib.fluid.FluidStack;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/** Registry-aware Fabric serialization of the canonical BCCE fluid-stack NBT. */
public final class FluidCompat {
    private FluidCompat() {}

    public static Component getDisplayName(FluidStack stack) {
        return stack == null || stack.isEmpty() ? Component.empty() : stack.getHoverName();
    }

    public static String getTranslationKey(FluidStack stack) {
        return stack == null || stack.isEmpty() ? "" : stack.getDescriptionId();
    }

    /** Preserves the exact id/amount/components envelope used by NeoForge 26.3. */
    public static CompoundTag saveOptional(FluidStack stack, HolderLookup.Provider registries) {
        CompoundTag result = new CompoundTag();
        if (stack == null || stack.isEmpty() || registries == null) return result;
        Identifier id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        if (id == null) return result;
        result.putString("id", id.toString());
        result.putInt("amount", stack.getAmount());
        if (!stack.getComponentsPatch().isEmpty()) {
            DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            Tag encoded = DataComponentPatch.CODEC.encodeStart(ops, stack.getComponentsPatch())
                .getOrThrow();
            if (!(encoded instanceof CompoundTag compound)) {
                throw new IllegalStateException("Fluid Data Components did not encode as compound NBT");
            }
            result.put("components", compound);
        }
        return result;
    }

    /** A nonempty, invalid component payload must never degrade to a bare fluid. */
    public static FluidStack parseOptional(HolderLookup.Provider registries, CompoundTag tag) {
        if (registries == null || tag == null || tag.isEmpty()) return FluidStack.EMPTY;
        Identifier id = Identifier.tryParse(NbtCompat.getString(tag, "id"));
        int amount = NbtCompat.getInt(tag, "amount");
        if (id == null || amount <= 0) return FluidStack.EMPTY;
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) return FluidStack.EMPTY;
        DataComponentPatch components = DataComponentPatch.EMPTY;
        if (NbtCompat.contains(tag, "components", Tag.TAG_COMPOUND)) {
            CompoundTag raw = NbtCompat.getCompound(tag, "components");
            try {
                DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
                components = DataComponentPatch.CODEC.parse(ops, raw).getOrThrow();
            } catch (RuntimeException invalidComponents) {
                return FluidStack.EMPTY;
            }
        }
        return new FluidStack(fluid, amount, components);
    }
}
