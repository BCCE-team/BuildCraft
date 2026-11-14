/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidComponentPayload;
import buildcraft.lib.internal.data.NbtSquishConstants;
import buildcraft.lib.nbt.NbtSquisher;
import com.mojang.serialization.DynamicOps;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * BCCE 26.3 fluid Data Components serializer shared with NeoForge API2.
 *
 * The API variant uses an opaque compressed NBT payload with exactly the
 * format ID and NbtSquisher encoding already used by FuelApiBridge in NeoForge.
 * No components are ever silently discarded. A foreign/invalid payload or
 * unsupported codec returns empty and transfers NO fluid.
 */
public final class FabricFluidVariants {
    public static final Identifier COMPONENT_FORMAT =
        Identifier.fromNamespaceAndPath("buildcraftlib", "neoforge_fluid_components");

    private FabricFluidVariants() {}

    public static Optional<buildcraft.api.v2.fluid.FluidVariant> toApi(
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant variant,
        HolderLookup.Provider registries
    ) {
        if (variant == null || variant.isBlank() || variant.getFluid() == Fluids.EMPTY) {
            return Optional.empty();
        }
        Identifier id = BuiltInRegistries.FLUID.getKey(variant.getFluid());
        if (id == null) return Optional.empty();
        DataComponentPatch patch = variant.getComponentsPatch();
        if (patch == null || patch.isEmpty()) {
            return Optional.of(buildcraft.api.v2.fluid.FluidVariant.of(id));
        }
        try {
            DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            Tag data = DataComponentPatch.CODEC.encodeStart(ops, patch).getOrThrow();
            if (!(data instanceof CompoundTag compound)) return Optional.empty();
            byte[] compressed = NbtSquisher.squish(compound, NbtSquishConstants.VANILLA);
            return Optional.of(buildcraft.api.v2.fluid.FluidVariant.of(id,
                FluidComponentPayload.of(COMPONENT_FORMAT, compressed)));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public static Optional<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> fromApi(
        buildcraft.api.v2.fluid.FluidVariant api,
        HolderLookup.Provider registries
    ) {
        if (api == null) return Optional.empty();
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(api.fluidId()).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) return Optional.empty();

        FluidComponentPayload payload = api.components();
        if (payload.isEmpty()) {
            return Optional.of(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid));
        }
        if (!payload.formatId().filter(COMPONENT_FORMAT::equals).isPresent()) {
            return Optional.empty();
        }
        try {
            CompoundTag components = NbtSquisher.expand(payload.copyCanonicalBytes());
            DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, registries);
            DataComponentPatch decoded = DataComponentPatch.CODEC.parse(ops, components).getOrThrow();
            return Optional.of(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid, decoded));
        } catch (RuntimeException | IOException exception) {
            return Optional.empty();
        }
    }
}
