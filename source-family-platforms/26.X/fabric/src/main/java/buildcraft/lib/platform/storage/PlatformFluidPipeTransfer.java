/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.storage;

import java.util.Optional;
import javax.annotation.Nullable;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.drop.FluidDropContext;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.fluid.FabricFluidVariants;
import buildcraft.lib.internal.transfer.FabricTransactionScope;
import buildcraft.lib.misc.ItemStackUtil;
import com.mojang.serialization.DynamicOps;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Fabric 26.3 fluid pipe interop. Uses the same API v2 FluidPort and canonical
 * component-bearing FluidVolume as the native BCCE NeoForge gameplay layer.
 *
 * <p>Never consult an unloaded chunk. A minimum-size extraction executes in
 * a parent Fabric transaction so a changing endpoint cannot cause a partial
 * drain after its simulation passed.</p>
 */
public final class PlatformFluidPipeTransfer {
    private PlatformFluidPipeTransfer() {}

    @Nullable
    private static FluidPort port(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return null;
        return BuildCraftApi.service(BuildCraftServices.PLATFORM)
            .fluidTransfer()
            .flatMap(transfer -> transfer.find(level, pos, side))
            .orElse(null);
    }

    public static boolean canConnect(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side
    ) {
        return port(level, pos, side) != null;
    }

    public static FluidVolume extract(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side,
        @Nullable FluidMatcher matcher, @Nullable FluidVolume preferred,
        int min, int max, OperationMode mode
    ) {
        if (max <= 0 || min < 0 || min > max) return FluidVolume.empty();
        FluidPort target = port(level, pos, side);
        if (target == null) return FluidVolume.empty();

        FluidMatcher effective = matcher == null ? FluidMatcher.any() : matcher;
        if (preferred != null && !preferred.isEmpty()) {
            effective = FluidMatcher.exact(preferred.requireVariant()).and(effective);
        }
        FluidAmount limit = FluidAmount.of(max);
        FluidTransferResult simulated = target.extract(effective, limit, OperationMode.SIMULATE);
        if (simulated.transferredAmount().milliBuckets() < min) return FluidVolume.empty();
        if (mode == OperationMode.SIMULATE) return simulated.transferred();

        try (Transaction transaction = FabricTransactionScope.begin()) {
            FluidTransferResult executed = target.extract(effective, limit, OperationMode.EXECUTE);
            if (executed.transferredAmount().milliBuckets() < min) {
                // Closing the parent without commit aborts the nested API2 action.
                return FluidVolume.empty();
            }
            if (executed.transferredAmount().isZero()) return FluidVolume.empty();
            transaction.commit();
            return executed.transferred();
        }
    }

    public static FluidAmount insert(
        Level level, BlockPos pos, @Nullable BlockEntity tile, Direction side,
        FluidVolume offered, OperationMode mode
    ) {
        if (offered == null || offered.isEmpty()) return FluidAmount.ZERO;
        FluidPort target = port(level, pos, side);
        if (target == null) return FluidAmount.ZERO;
        return target.insert(offered, mode).transferredAmount();
    }

    /** Apply Fabric's dynamic fluid-tag membership when evaluating an API2 matcher. */
    public static boolean matches(FluidMatcher matcher, FluidVolume candidate) {
        if (matcher == null || candidate == null || candidate.isEmpty()) return false;
        Identifier fluidId = candidate.requireVariant().fluidId();
        return matcher.matches(candidate.requireVariant(),
            (fluidKey, tagKey) -> {
                Fluid tested = BuiltInRegistries.FLUID.getOptional(fluidKey).orElse(Fluids.EMPTY);
                return tested != Fluids.EMPTY && tested.is(net.minecraft.tags.TagKey.create(
                    net.minecraft.core.registries.Registries.FLUID, tagKey));
            });
    }

    public static boolean equivalent(@Nullable FluidVolume first, @Nullable FluidVolume second) {
        if (first == null || first.isEmpty() || second == null || second.isEmpty()) {
            return (first == null || first.isEmpty()) && (second == null || second.isEmpty());
        }
        return canonicalize(first).requireVariant().equals(canonicalize(second).requireVariant());
    }

    /**
     * Validate/canonicalize a component payload without silently dropping it.
     * Unknown formats remain opaque; their variant identity must not change.
     */
    public static FluidVolume canonicalize(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidVolume.empty();
        var decoded = FabricFluidVariants.fromApi(
            volume.requireVariant(), ItemStackUtil.requireActiveRegistryProvider());
        if (decoded.isEmpty()) return volume;
        var reencoded = FabricFluidVariants.toApi(
            decoded.get(), ItemStackUtil.requireActiveRegistryProvider());
        return reencoded.map(variant -> FluidVolume.of(variant, volume.amount())).orElse(volume);
    }

    /**
     * Decode legacy Forge/NeoForge fluids using the same modern custom_data
     * migration semantics as the NeoForge FluidStackUtil normalization.
     * Invalid identities, zero amounts and unrepresentable components fail closed.
     */
    public static FluidVolume readLegacyNbt(CompoundTag original) {
        return readLegacyNbt(original, ItemStackUtil.requireActiveRegistryProvider());
    }

    /** Registry-aware overload for level/chunk NBT data loaded before global server lookup exists. */
    public static FluidVolume readLegacyNbt(CompoundTag original, HolderLookup.Provider registries) {
        if (original == null || original.isEmpty() || registries == null) return FluidVolume.empty();
        CompoundTag tag = !NbtCompat.contains(original, "id", Tag.TAG_STRING)
            && NbtCompat.contains(original, "Fluid", Tag.TAG_COMPOUND)
            ? NbtCompat.getCompound(original, "Fluid") : original;
        String name = NbtCompat.contains(tag, "id", Tag.TAG_STRING)
            ? NbtCompat.getString(tag, "id")
            : NbtCompat.contains(tag, "FluidName", Tag.TAG_STRING)
                ? NbtCompat.getString(tag, "FluidName")
                : NbtCompat.contains(tag, "FluidType", Tag.TAG_STRING)
                    ? NbtCompat.getString(tag, "FluidType")
                    : NbtCompat.getString(tag, "fluid");
        Identifier id = Identifier.tryParse(name);
        if (id == null) return FluidVolume.empty();
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(id).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) return FluidVolume.empty();
        int amount = NbtCompat.contains(tag, "amount", 99)
            ? NbtCompat.getInt(tag, "amount") : NbtCompat.getInt(tag, "Amount");
        if (amount <= 0) return FluidVolume.empty();

        DataComponentPatch components = DataComponentPatch.EMPTY;
        try {
            CompoundTag serializedComponents = NbtCompat.contains(tag, "components", Tag.TAG_COMPOUND)
                ? NbtCompat.getCompound(tag, "components") : null;
            if (serializedComponents != null) {
                DynamicOps<Tag> ops = RegistryOps.create(
                    NbtOps.INSTANCE, registries);
                components = DataComponentPatch.CODEC.parse(ops, serializedComponents).getOrThrow();
            } else {
                CompoundTag legacy = NbtCompat.contains(tag, "Tag", Tag.TAG_COMPOUND)
                    ? NbtCompat.getCompound(tag, "Tag")
                    : NbtCompat.getCompound(tag, "tag");
                if (!legacy.isEmpty()) {
                    components = DataComponentPatch.builder().set(
                        DataComponents.CUSTOM_DATA, CustomData.of(legacy.copy())).build();
                }
            }
            var nativeVariant = net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid, components);
            return FabricFluidVariants.toApi(
                nativeVariant, registries)
                .map(variant -> FluidVolume.of(variant, FluidAmount.of(amount)))
                .orElseGet(FluidVolume::empty);
        } catch (RuntimeException invalidComponents) {
            // Never migrate an undecodable decorated fluid to an undecorated one.
            return FluidVolume.empty();
        }
    }

    /** Localized Fabric fluid name, including registered modded-fluid name providers. */
    public static Component displayName(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return Component.translatable("buildcraft.fluid.empty");
        Optional<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> decoded =
            FabricFluidVariants.fromApi(volume.requireVariant(), ItemStackUtil.requireActiveRegistryProvider());
        return decoded.map(FluidVariantAttributes::getName)
            .orElseGet(() -> Component.literal(volume.requireVariant().fluidId().toString()));
    }

    /** Exactly the same fluid-drop service as NeoForge's FluidDropRuntime. */
    public static void addFluidDrops(NonNullList<ItemStack> drops, FluidVolume volume) {
        if (drops == null || volume == null || volume.isEmpty()) return;
        for (ItemStack drop : BuildCraftApi.service(BuildCraftServices.FLUID_DROPS)
            .createDrops(FluidDropContext.of(volume))) {
            if (drop != null && !drop.isEmpty()) drops.add(drop.copy());
        }
    }
}
