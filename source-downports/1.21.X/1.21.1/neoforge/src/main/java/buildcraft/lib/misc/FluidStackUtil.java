package buildcraft.lib.misc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

/** Compatibility helpers for NeoForge's data-component based FluidStack API. */
public final class FluidStackUtil {
    private FluidStackUtil() {
    }

    @Nonnull
    public static CompoundTag saveOptional(@Nonnull FluidStack stack) {
        return saveOptional(stack, ItemStackUtil.requireActiveRegistryProvider());
    }

    @Nonnull
    public static CompoundTag saveOptional(@Nonnull FluidStack stack, @Nonnull HolderLookup.Provider registries) {
        Tag tag = stack.saveOptional(registries);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    @Nonnull
    public static FluidStack parseOptional(@Nullable CompoundTag tag) {
        return parseOptional(ItemStackUtil.requireActiveRegistryProvider(), tag);
    }

    /** Reads both NeoForge 1.21.1 and Forge 1.20.1 FluidStack NBT. */
    @Nonnull
    public static FluidStack parseOptional(@Nonnull HolderLookup.Provider registries, @Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return FluidStack.EMPTY;
        }

        CompoundTag normalized = normalizeLegacyNbt(tag);
        if (!normalized.contains("id", Tag.TAG_STRING)) {
            return FluidStack.EMPTY;
        }

        String idString = normalized.getString("id");
        int amount = normalized.contains("amount", Tag.TAG_ANY_NUMERIC) ? normalized.getInt("amount") : 0;
        if (amount <= 0 || "minecraft:empty".equals(idString)) {
            return FluidStack.EMPTY;
        }

        try {
            FluidStack parsed = FluidStack.parseOptional(registries, normalized);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Preserve the block entity by falling back to the registry id and amount.
        }

        ResourceLocation id = ResourceLocation.tryParse(idString);
        if (id == null) {
            return FluidStack.EMPTY;
        }
        Fluid fluid = BuiltInRegistries.FLUID.get(id);
        return fluid == null || fluid == Fluids.EMPTY ? FluidStack.EMPTY : new FluidStack(fluid, amount);
    }

    /**
     * Converts Forge's legacy {@code FluidName}/{@code Amount}/{@code Tag} form to
     * NeoForge 1.21.1's {@code id}/{@code amount}/{@code components} form.
     */
    @Nonnull
    public static CompoundTag normalizeLegacyNbt(@Nonnull CompoundTag source) {
        if (!source.contains("id", Tag.TAG_STRING) && source.contains("Fluid", Tag.TAG_COMPOUND)) {
            return normalizeLegacyNbt(source.getCompound("Fluid"));
        }

        CompoundTag normalized = source.copy();
        if (!normalized.contains("id", Tag.TAG_STRING)) {
            if (normalized.contains("FluidName", Tag.TAG_STRING)) {
                normalized.putString("id", normalized.getString("FluidName"));
            } else if (normalized.contains("FluidType", Tag.TAG_STRING)) {
                normalized.putString("id", normalized.getString("FluidType"));
            } else if (normalized.contains("fluid", Tag.TAG_STRING)) {
                normalized.putString("id", normalized.getString("fluid"));
            }
        }
        if (!normalized.contains("amount", Tag.TAG_ANY_NUMERIC)
            && normalized.contains("Amount", Tag.TAG_ANY_NUMERIC)) {
            normalized.putInt("amount", normalized.getInt("Amount"));
        }

        CompoundTag legacyData = new CompoundTag();
        if (source.contains("Tag", Tag.TAG_COMPOUND)) {
            legacyData = source.getCompound("Tag").copy();
        } else if (source.contains("tag", Tag.TAG_COMPOUND)) {
            legacyData = source.getCompound("tag").copy();
        }
        if (!legacyData.isEmpty()) {
            CompoundTag components = normalized.contains("components", Tag.TAG_COMPOUND)
                ? normalized.getCompound("components").copy()
                : new CompoundTag();
            if (!components.contains("minecraft:custom_data")) {
                components.put("minecraft:custom_data", legacyData);
            }
            normalized.put("components", components);
        }
        return normalized;
    }

    @Nonnull
    public static FluidStack copyWithFluid(@Nonnull FluidStack stack, @Nonnull Fluid fluid) {
        if (stack.isEmpty()) {
            return FluidStack.EMPTY;
        }
        return new FluidStack(fluid.builtInRegistryHolder(), stack.getAmount(), stack.getComponentsPatch());
    }

    @Nonnull
    private static RegistryFriendlyByteBuf registryBuffer(@Nonnull FriendlyByteBuf buffer) {
        if (buffer instanceof RegistryFriendlyByteBuf registryBuffer) {
            return registryBuffer;
        }
        RegistryAccess registries = ItemStackUtil.getActiveRegistryAccess();
        return new RegistryFriendlyByteBuf(buffer, registries);
    }

    public static void write(@Nonnull FriendlyByteBuf buffer, @Nonnull FluidStack stack) {
        FluidStack.OPTIONAL_STREAM_CODEC.encode(registryBuffer(buffer), stack);
    }

    @Nonnull
    public static FluidStack read(@Nonnull FriendlyByteBuf buffer) {
        return FluidStack.OPTIONAL_STREAM_CODEC.decode(registryBuffer(buffer));
    }
}
