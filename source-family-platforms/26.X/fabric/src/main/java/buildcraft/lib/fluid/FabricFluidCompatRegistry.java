/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.fluid.FluidVolume;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Fabric 26.3 counterpart to NeoForge FluidCompatRegistry.
 *
 * <p>BuildCraft's canonical-fluid equivalence is deliberately separate from
 * recipe tag matching. A registration declares that all fluids in a tag (and
 * optional legacy aliases) are interchangeable in tanks, while canonicalizing
 * the stored registry ID. Opaque component payloads are never discarded:
 * variants with different components are never equivalent.</p>
 *
 * <p>Tag membership is evaluated against the current registry on every query,
 * so datapack reloads do not leave a stale fluid-equivalence cache.</p>
 */
public final class FabricFluidCompatRegistry {
    private static final List<FluidGroup> GROUPS = new CopyOnWriteArrayList<>();

    private FabricFluidCompatRegistry() {}

    public static synchronized void registerCanonical(
        TagKey<Fluid> tag, Fluid canonical, Identifier... fallbackAliases
    ) {
        if (tag == null || canonical == null || canonical == Fluids.EMPTY) return;
        for (FluidGroup group : GROUPS) {
            if (group.tag.equals(tag)) {
                group.canonical = canonical;
                if (fallbackAliases != null) Collections.addAll(group.fallbackAliases, fallbackAliases);
                return;
            }
        }
        FluidGroup group = new FluidGroup(tag, canonical);
        if (fallbackAliases != null) Collections.addAll(group.fallbackAliases, fallbackAliases);
        GROUPS.add(group);
    }

    public static FluidVolume canonicalize(FluidVolume volume) {
        if (volume == null || volume.isEmpty()) return FluidVolume.empty();
        FluidVariant variant = volume.requireVariant();
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(variant.fluidId()).orElse(Fluids.EMPTY);
        if (fluid == Fluids.EMPTY) return volume;
        for (FluidGroup group : GROUPS) {
            if (!group.matches(fluid)) continue;
            Identifier canonicalId = BuiltInRegistries.FLUID.getKey(group.canonical);
            if (canonicalId == null || canonicalId.equals(variant.fluidId())) return volume;
            return FluidVolume.of(FluidVariant.of(canonicalId, variant.components()), volume.amount());
        }
        return volume;
    }

    public static boolean areEquivalent(FluidVolume first, FluidVolume second) {
        if (first == null || second == null) return first == second;
        if (first.isEmpty() || second.isEmpty()) return first.isEmpty() && second.isEmpty();
        FluidVariant a = first.requireVariant();
        FluidVariant b = second.requireVariant();
        if (!a.components().equals(b.components())) return false;
        if (a.fluidId().equals(b.fluidId())) return true;
        Fluid firstFluid = BuiltInRegistries.FLUID.getOptional(a.fluidId()).orElse(Fluids.EMPTY);
        Fluid secondFluid = BuiltInRegistries.FLUID.getOptional(b.fluidId()).orElse(Fluids.EMPTY);
        if (firstFluid == Fluids.EMPTY || secondFluid == Fluids.EMPTY) return false;
        for (FluidGroup group : GROUPS) {
            if (group.matches(firstFluid) && group.matches(secondFluid)) return true;
        }
        return false;
    }

    private static final class FluidGroup {
        private final TagKey<Fluid> tag;
        private final Set<Identifier> fallbackAliases = new HashSet<>();
        private Fluid canonical;

        private FluidGroup(TagKey<Fluid> tag, Fluid canonical) {
            this.tag = Objects.requireNonNull(tag, "tag");
            this.canonical = Objects.requireNonNull(canonical, "canonical");
        }

        private boolean matches(Fluid fluid) {
            if (fluid == canonical) return true;
            Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
            return (id != null && fallbackAliases.contains(id)) || fluid.is(tag);
        }
    }
}
