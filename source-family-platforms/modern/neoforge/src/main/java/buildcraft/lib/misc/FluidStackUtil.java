//? source if >=1.21.1
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
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.compat.FluidCompat;

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
        Tag tag = FluidCompat.saveOptional(stack, registries);
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
        if (!NbtCompat.contains(normalized, "id", Tag.TAG_STRING)) {
            return FluidStack.EMPTY;
        }

        String idString = NbtCompat.getString(normalized, "id");
        int amount = NbtCompat.contains(normalized, "amount", 99) ? NbtCompat.getInt(normalized, "amount") : 0;
        if (amount <= 0 || "minecraft:empty".equals(idString)) {
            return FluidStack.EMPTY;
        }

        try {
            FluidStack parsed = FluidCompat.parseOptional(registries, normalized);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Preserve the block entity by falling back to the registry id and amount.
        }

        Identifier id = Identifier.tryParse(idString);
        if (id == null) {
            return FluidStack.EMPTY;
        }
        Fluid fluid = BuiltInRegistries.FLUID.get(id).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.level.material.Fluids.EMPTY);
        return fluid == null || fluid == Fluids.EMPTY ? FluidStack.EMPTY : new FluidStack(fluid, amount);
    }

    /**
     * Converts Forge's legacy {@code FluidName}/{@code Amount}/{@code Tag} form to
     * NeoForge 1.21.1's {@code id}/{@code amount}/{@code components} form.
     */
    @Nonnull
    public static CompoundTag normalizeLegacyNbt(@Nonnull CompoundTag source) {
        if (!NbtCompat.contains(source, "id", Tag.TAG_STRING) && NbtCompat.contains(source, "Fluid", Tag.TAG_COMPOUND)) {
            return normalizeLegacyNbt(NbtCompat.getCompound(source, "Fluid"));
        }

        CompoundTag normalized = source.copy();
        if (!NbtCompat.contains(normalized, "id", Tag.TAG_STRING)) {
            if (NbtCompat.contains(normalized, "FluidName", Tag.TAG_STRING)) {
                normalized.putString("id", NbtCompat.getString(normalized, "FluidName"));
            } else if (NbtCompat.contains(normalized, "FluidType", Tag.TAG_STRING)) {
                normalized.putString("id", NbtCompat.getString(normalized, "FluidType"));
            } else if (NbtCompat.contains(normalized, "fluid", Tag.TAG_STRING)) {
                normalized.putString("id", NbtCompat.getString(normalized, "fluid"));
            }
        }
        if (!NbtCompat.contains(normalized, "amount", 99)
            && NbtCompat.contains(normalized, "Amount", 99)) {
            normalized.putInt("amount", NbtCompat.getInt(normalized, "Amount"));
        }

        CompoundTag legacyData = new CompoundTag();
        if (NbtCompat.contains(source, "Tag", Tag.TAG_COMPOUND)) {
            legacyData = NbtCompat.getCompound(source, "Tag").copy();
        } else if (NbtCompat.contains(source, "tag", Tag.TAG_COMPOUND)) {
            legacyData = NbtCompat.getCompound(source, "tag").copy();
        }
        if (!legacyData.isEmpty()) {
            CompoundTag components = NbtCompat.contains(normalized, "components", Tag.TAG_COMPOUND)
                ? NbtCompat.getCompound(normalized, "components").copy()
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
