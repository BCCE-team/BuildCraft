/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.lib.misc.ItemStackUtil;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Minecraft 26.3 Fabric Transfer API -> canonical BCCE fluid variant bridge.
 *
 * <p>The old platform-layer implementation serialized 1.20.1 FluidVariant NBT
 * into an SNBT payload. That representation is invalid in Minecraft 26.3,
 * where FluidVariant carries a DataComponentPatch. Delegating to the 26.X
 * codec keeps the same component format as the NeoForge API v2 bridge.
 *
 * <p>Unsupported or malformed component payloads are rejected. Never
 * reinterpret a decorated fluid as its undecorated counterpart: doing so
 * merges distinct tank contents and breaks fluid conservation.
 */
public final class FabricFluidVariants {
    public static final FluidMatchContext MATCH_CONTEXT = FabricFluidVariants::isInTag;

    private FabricFluidVariants() {
    }

    public static Optional<FluidVariant> tryToApi(
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant variant
    ) {
        return buildcraft.lib.fluid.FabricFluidVariants.toApi(
            variant, ItemStackUtil.requireActiveRegistryProvider()
        );
    }

    /** Compatibility entrypoint for code which already validated the native view. */
    public static FluidVariant toApi(
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant variant
    ) {
        return tryToApi(variant).orElseThrow(() ->
            new IllegalArgumentException("Unrepresentable Minecraft 26.3 Fabric fluid variant")
        );
    }

    public static Optional<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> fromApi(
        FluidVariant variant
    ) {
        return buildcraft.lib.fluid.FabricFluidVariants.fromApi(
            variant, ItemStackUtil.requireActiveRegistryProvider()
        );
    }

    private static boolean isInTag(Identifier fluidId, Identifier tagId) {
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(fluidId).orElse(Fluids.EMPTY);
        return fluid != Fluids.EMPTY && fluid.is(TagKey.create(Registries.FLUID, tagId));
    }
}
