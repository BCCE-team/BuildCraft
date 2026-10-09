/**
 * Copyright (c) the BuildCraft team, 2026.
 * This file is part of BuildCraft, licensed under the LGPLv3.
 */
package buildcraft.lib.misc;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.compat.ItemCompat;

/** Compatibility helpers for the Minecraft 1.20.5+ ItemStack data-component API. */
public final class ItemStackUtil {
    private ItemStackUtil() {
    }

    @Nonnull
    public static CompoundTag getCustomData(@Nonnull ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    @Nullable
    public static CompoundTag getCustomDataOrNull(@Nonnull ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : data.copyTag();
    }

    public static boolean hasCustomData(@Nonnull ItemStack stack) {
        CompoundTag tag = getCustomDataOrNull(stack);
        return tag != null && !tag.isEmpty();
    }

    public static void setCustomData(@Nonnull ItemStack stack, @Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag.copy()));
        }
    }

    /** Static registry context used only before a server/client world is available. */
    private static final RegistryAccess BUILTIN_REGISTRIES =
        RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    /** Client-side registry lookup cached while a world is connected. */
    @Nullable
    private static volatile RegistryAccess clientRegistries;

    public static void setClientRegistryProvider(@Nullable HolderLookup.Provider registries) {
        clientRegistries = registries instanceof RegistryAccess access ? access : null;
    }

    /**
     * Returns the active server registry lookup, the connected client world's lookup,
     * or a built-in registry lookup during early mod lifecycle events.
     */
    @Nonnull
    public static HolderLookup.Provider getActiveRegistryProvider() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.registryAccess();
        }
        RegistryAccess client = clientRegistries;
        return client != null ? client : BUILTIN_REGISTRIES;
    }

    @Nonnull
    public static HolderLookup.Provider requireActiveRegistryProvider() {
        return getActiveRegistryProvider();
    }

    /** Registry access required by RegistryFriendlyByteBuf. */
    @Nonnull
    public static RegistryAccess getActiveRegistryAccess() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.registryAccess();
        }
        RegistryAccess client = clientRegistries;
        return client != null ? client : BUILTIN_REGISTRIES;
    }

    /**
     * Compatibility overload for APIs that cannot pass a lookup explicitly. It is
     * always registry-aware; callers with a world/provider should prefer the explicit overload.
     */
    @Nonnull
    public static CompoundTag saveOptional(@Nonnull ItemStack stack) {
        return saveOptional(stack, requireActiveRegistryProvider());
    }

    /** Counterpart to {@link #saveOptional(ItemStack)}. */
    @Nonnull
    public static ItemStack parseOptional(@Nullable CompoundTag tag) {
        return parseOptional(requireActiveRegistryProvider(), tag);
    }

    /**
     * Reads both the current component-based stack format and BuildCraft data saved
     * by Forge/Minecraft 1.20.1 ({@code Count}/{@code tag}).
     */
    @Nonnull
    public static ItemStack parseOptional(@Nonnull HolderLookup.Provider registries, @Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return ItemStack.EMPTY;
        }

        CompoundTag normalized = normalizeLegacyNbt(tag);
        if (!NbtCompat.contains(normalized, "id", Tag.TAG_STRING)) {
            // Empty statement parameters legitimately contain only their BuildCraft "kind".
            // Feeding those compounds to ItemStack's codec logs an error and can abort gate sync.
            return ItemStack.EMPTY;
        }

        String idString = NbtCompat.getString(normalized, "id");
        Identifier id = Identifier.tryParse(idString);
        int count = NbtCompat.contains(normalized, "count", 99) ? NbtCompat.getInt(normalized, "count") : 1;
        if (id == null || count <= 0 || "minecraft:air".equals(idString)) {
            return ItemStack.EMPTY;
        }

        try {
            ItemStack parsed = ItemCompat.parseOptional(registries, normalized);
            if (!parsed.isEmpty()) {
                return parsed;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the conservative reader. It deliberately retains the
            // legacy tag as custom data instead of causing the whole block entity to fail.
        }

        Item item = BuiltInRegistries.ITEM.get(id).map(net.minecraft.core.Holder.Reference::value).orElse(net.minecraft.world.item.Items.AIR);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack fallback = new ItemStack(item, count);
        CompoundTag legacyData = getLegacyCustomData(tag);
        if (!legacyData.isEmpty()) {
            setCustomData(fallback, legacyData);
        }
        if (NbtCompat.contains(legacyData, "Damage", 99)) {
            fallback.set(DataComponents.DAMAGE, Math.max(0, NbtCompat.getInt(legacyData, "Damage")));
        }
        return fallback;
    }

    /**
     * Converts the pre-1.20.5 ItemStack representation ({@code Count}/{@code tag})
     * into the native component-based representation.
     */
    @Nonnull
    public static CompoundTag normalizeLegacyNbt(@Nonnull CompoundTag source) {
        if (!NbtCompat.contains(source, "id", Tag.TAG_STRING) && NbtCompat.contains(source, "stack", Tag.TAG_COMPOUND)) {
            return normalizeLegacyNbt(NbtCompat.getCompound(source, "stack"));
        }

        CompoundTag normalized = source.copy();
        if (!NbtCompat.contains(normalized, "id", Tag.TAG_STRING)) {
            if (NbtCompat.contains(normalized, "Item", Tag.TAG_STRING)) {
                normalized.putString("id", NbtCompat.getString(normalized, "Item"));
            } else if (NbtCompat.contains(normalized, "item", Tag.TAG_STRING)) {
                normalized.putString("id", NbtCompat.getString(normalized, "item"));
            }
        }
        if (!NbtCompat.contains(normalized, "count", 99)) {
            if (NbtCompat.contains(normalized, "Count", 99)) {
                normalized.putInt("count", NbtCompat.getInt(normalized, "Count"));
            } else if (NbtCompat.contains(normalized, "id", Tag.TAG_STRING)) {
                normalized.putInt("count", 1);
            }
        }

        CompoundTag legacyData = getLegacyCustomData(source);
        if (!legacyData.isEmpty()) {
            CompoundTag components = NbtCompat.contains(normalized, "components", Tag.TAG_COMPOUND)
                ? NbtCompat.getCompound(normalized, "components").copy()
                : new CompoundTag();
            if (!components.contains("minecraft:custom_data")) {
                components.put("minecraft:custom_data", legacyData.copy());
            }
            if (!components.contains("minecraft:damage")
                && NbtCompat.contains(legacyData, "Damage", 99)) {
                components.putInt("minecraft:damage", Math.max(0, NbtCompat.getInt(legacyData, "Damage")));
            }
            normalized.put("components", components);
        }
        return normalized;
    }

    @Nonnull
    private static CompoundTag getLegacyCustomData(@Nonnull CompoundTag source) {
        if (NbtCompat.contains(source, "tag", Tag.TAG_COMPOUND)) {
            return NbtCompat.getCompound(source, "tag").copy();
        }
        if (NbtCompat.contains(source, "Tag", Tag.TAG_COMPOUND)) {
            return NbtCompat.getCompound(source, "Tag").copy();
        }
        return new CompoundTag();
    }

    @Nonnull
    public static CompoundTag saveOptional(@Nonnull ItemStack stack, @Nonnull HolderLookup.Provider registries) {
        Tag tag = ItemCompat.saveOptional(stack, registries);
        return tag instanceof CompoundTag compound ? compound : new CompoundTag();
    }

    @Nonnull
    private static RegistryFriendlyByteBuf registryBuffer(@Nonnull FriendlyByteBuf buffer) {
        if (buffer instanceof RegistryFriendlyByteBuf registryBuffer) {
            return registryBuffer;
        }
        // BuildCraft has several length-prefixed nested payloads represented as a plain
        // FriendlyByteBuf. Wrap the same backing buffer with the active registry lookup.
        return new RegistryFriendlyByteBuf(buffer, getActiveRegistryAccess());
    }

    public static void write(@Nonnull FriendlyByteBuf buffer, @Nonnull ItemStack stack) {
        ItemStack.STREAM_CODEC.encode(registryBuffer(buffer), stack);
    }

    @Nonnull
    public static ItemStack read(@Nonnull FriendlyByteBuf buffer) {
        return ItemStack.STREAM_CODEC.decode(registryBuffer(buffer));
    }

    public static void writeOptional(@Nonnull FriendlyByteBuf buffer, @Nonnull ItemStack stack) {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(registryBuffer(buffer), stack);
    }

    @Nonnull
    public static ItemStack readOptional(@Nonnull FriendlyByteBuf buffer) {
        return ItemStack.OPTIONAL_STREAM_CODEC.decode(registryBuffer(buffer));
    }
}
