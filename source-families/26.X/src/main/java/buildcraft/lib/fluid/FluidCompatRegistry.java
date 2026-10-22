package buildcraft.lib.fluid;

import buildcraft.lib.misc.FluidStackUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;

import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Central fluid equivalence registry used by optional mod integrations.
 *
 * <p>Forge fluid tags describe fluids that are interchangeable from a recipe
 * point of view. BuildCraft additionally stores one canonical fluid per tag so
 * its tanks can migrate compatible foreign fluids to a stable internal fluid
 * ID. This avoids splitting otherwise identical fluids between tanks, recipes,
 * pipe filters and machines.</p>
 */
public final class FluidCompatRegistry {
    private static final List<FluidGroup> GROUPS = new CopyOnWriteArrayList<>();

    private FluidCompatRegistry() {}

    public static synchronized void registerCanonical(TagKey<Fluid> tag, Fluid canonical,
        Identifier... fallbackAliases) {
        if (tag == null || canonical == null || canonical == Fluids.EMPTY) {
            return;
        }
        for (FluidGroup group : GROUPS) {
            if (group.tag.equals(tag)) {
                group.canonical = canonical;
                Collections.addAll(group.fallbackAliases, fallbackAliases);
                return;
            }
        }
        FluidGroup group = new FluidGroup(tag, canonical);
        Collections.addAll(group.fallbackAliases, fallbackAliases);
        GROUPS.add(group);
    }

    public static boolean areEquivalent(@Nullable FluidStack first, @Nullable FluidStack second) {
        if (first == null || second == null) {
            return first == second;
        }
        Fluid firstFluid = first.getFluid();
        Fluid secondFluid = second.getFluid();
        if (firstFluid == Fluids.EMPTY || secondFluid == Fluids.EMPTY) {
            return firstFluid == secondFluid;
        }
        return areEquivalent(firstFluid, secondFluid)
            && Objects.equals(first.getComponents(), second.getComponents());
    }

    public static boolean areEquivalent(Fluid first, Fluid second) {
        if (first == second) {
            return true;
        }
        if (first != null && second != null && first.getFluidType() == second.getFluidType()) {
            return true;
        }
        if (first == null || second == null || first == Fluids.EMPTY || second == Fluids.EMPTY) {
            return false;
        }
        for (FluidGroup group : GROUPS) {
            if (group.matches(first) && group.matches(second)) {
                return true;
            }
        }
        return false;
    }

    /** Converts a compatible foreign fluid to BuildCraft's canonical fluid. */
    public static FluidStack canonicalize(@Nullable FluidStack stack) {
        if (stack == null || stack.getFluid() == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        for (FluidGroup group : GROUPS) {
            if (group.matches(stack.getFluid())) {
                return copyWithFluid(stack, group.canonical);
            }
        }
        return stack;
    }

    /** Returns all registered fluids that are interchangeable with {@code stack}. */
    public static List<FluidStack> getEquivalentStacks(FluidStack stack, @Nullable String preferredNamespace) {
        if (stack == null || stack.getFluid() == Fluids.EMPTY) {
            return Collections.emptyList();
        }
        FluidGroup group = findGroup(stack.getFluid());
        if (group == null) {
            return Collections.singletonList(stack.copy());
        }

        Set<Fluid> fluids = new LinkedHashSet<>();
        fluids.add(stack.getFluid());
        fluids.add(group.canonical);
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
            if (id == null || isFlowingVariant(id)) {
                continue;
            }
            if (group.matches(fluid)) {
                fluids.add(fluid);
            }
        }

        List<Fluid> ordered = new ArrayList<>(fluids);
        ordered.sort(Comparator
            .comparingInt((Fluid fluid) -> namespaceRank(fluid, stack.getFluid(), preferredNamespace, group.canonical))
            .thenComparing(fluid -> {
                Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
                return id == null ? "" : id.toString();
            }));

        List<FluidStack> result = new ArrayList<>(ordered.size());
        for (Fluid fluid : ordered) {
            result.add(copyWithFluid(stack, fluid));
        }
        return result;
    }

    public static FluidStack copyWithFluid(FluidStack stack, Fluid fluid) {
        if (stack == null || stack.getFluid() == Fluids.EMPTY || fluid == null || fluid == Fluids.EMPTY) {
            return FluidStack.EMPTY;
        }
        if (stack.getFluid() == fluid) {
            return stack.copy();
        }
        return FluidStackUtil.copyWithFluid(stack, fluid);
    }

    private static int namespaceRank(Fluid fluid, Fluid requested, @Nullable String preferredNamespace,
        Fluid canonical) {
        if (fluid == requested) {
            return 0;
        }
        Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
        if (preferredNamespace != null && id != null && preferredNamespace.equals(id.getNamespace())) {
            return 1;
        }
        if (fluid == canonical) {
            return 2;
        }
        return 3;
    }

    @Nullable
    private static FluidGroup findGroup(Fluid fluid) {
        for (FluidGroup group : GROUPS) {
            if (group.matches(fluid)) {
                return group;
            }
        }
        return null;
    }

    private static boolean isFlowingVariant(Identifier id) {
        String path = id.getPath();
        return path.endsWith("_flowing") || path.startsWith("flowing_");
    }

    private static final class FluidGroup {
        private final TagKey<Fluid> tag;
        private final Set<Identifier> fallbackAliases = new HashSet<>();
        private Fluid canonical;

        private FluidGroup(TagKey<Fluid> tag, Fluid canonical) {
            this.tag = tag;
            this.canonical = canonical;
        }

        private boolean matches(Fluid fluid) {
            if (fluid == canonical) {
                return true;
            }
            Identifier id = BuiltInRegistries.FLUID.getKey(fluid);
            return (id != null && fallbackAliases.contains(id)) || fluid.is(tag);
        }
    }
}
