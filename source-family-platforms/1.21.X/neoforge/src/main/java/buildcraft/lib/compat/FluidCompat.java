//? source if >=1.21.11
package buildcraft.lib.compat;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.fluids.FluidStack;

/** 1.21.11 compatibility helpers for FluidStack APIs changed since 1.21.1. */
public final class FluidCompat {
    private FluidCompat() {
    }

    public static Component getDisplayName(FluidStack stack) {
        return stack == null || stack.isEmpty() ? Component.empty() : stack.getHoverName();
    }

    public static String getTranslationKey(FluidStack stack) {
        return stack == null || stack.isEmpty() ? "" : stack.getDescriptionId();
    }

    /** Serialize a FluidStack with NeoForge's registry-aware 1.21.11 codec. */
    public static CompoundTag saveOptional(FluidStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            return new CompoundTag();
        }
        Tag encoded = FluidStack.OPTIONAL_CODEC
            .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)
            .result()
            .orElse(null);
        return encoded instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    /** Decode the current 1.21.11 FluidStack codec representation. Legacy normalization is handled by FluidStackUtil. */
    public static FluidStack parseOptional(HolderLookup.Provider registries, CompoundTag tag) {
        if (registries == null || tag == null) return FluidStack.EMPTY;
        return FluidStack.OPTIONAL_CODEC
            .parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
            .result()
            .orElse(FluidStack.EMPTY);
    }
}
