package buildcraft.api.v2.recipe;

import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidVariant;
import buildcraft.api.v2.reload.DefinitionProvenance;
import buildcraft.api.v2.reload.DefinitionSnapshot;
import buildcraft.api.v2.reload.ResolvedDefinition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Authoritative addon-facing registry for BuildCraft machine recipes.
 *
 * Addons may register code-owned recipes. Datapack reload publication remains an
 * internal BuildCraft responsibility so external code cannot replace the live
 * registry snapshot out from under other consumers.
 */
public interface MachineRecipeService {
    DefinitionSnapshot<RecipeDefinition> snapshot();

    void register(ResourceLocation id, RecipeDefinition definition, DefinitionProvenance provenance);

    default Optional<RecipeMatch<IntegrationRecipeDefinition>> findIntegration(
        ItemStack target, NonNullList<ItemStack> toIntegrate
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(toIntegrate, "toIntegrate");
        for (ResolvedDefinition<RecipeDefinition> resolved : ordered()) {
            RecipeDefinition definition = resolved.value();
            try {
                IntegrationRecipeDefinition recipe = definition instanceof IntegrationRecipeDefinition direct ? direct
                    : definition instanceof IntegrationRecipeProvider provider
                    ? provider.recipeFor(target.copy(), copy(toIntegrate)) : null;
                if (recipe != null && !recipe.output(target.copy(), copy(toIntegrate)).isEmpty()) {
                    return Optional.of(match(resolved, recipe));
                }
            } catch (RuntimeException invalidAddonRecipe) {
                // Broken addon definitions must not crash the server's Integration Table tick.
            }
        }
        return Optional.empty();
    }

    /** Priority-ordered addon recipes; the returned objects do not expose mutable registry state. */
    default List<RecipeMatch<AssemblyRecipeDefinition>> assemblyRecipes() {
        return recipes(AssemblyRecipeDefinition.class);
    }

    default List<RecipeMatch<AssemblyRecipeDefinition>> findAssembly(NonNullList<ItemStack> available) {
        Objects.requireNonNull(available, "available");
        List<RecipeMatch<AssemblyRecipeDefinition>> matches = new ArrayList<>();
        for (RecipeMatch<AssemblyRecipeDefinition> candidate : assemblyRecipes()) {
            try {
                if (!candidate.recipe().outputs(copy(available)).isEmpty()) matches.add(candidate);
            } catch (RuntimeException invalidAddonRecipe) {
                // Reject a broken addon recipe without changing the live recipe snapshot.
            }
        }
        return List.copyOf(matches);
    }

    default Optional<RecipeMatch<AssemblyRecipeDefinition>> assemblyById(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        return snapshot().resolved(id)
            .filter(r -> r.value() instanceof AssemblyRecipeDefinition)
            .map(r -> match(r, (AssemblyRecipeDefinition) r.value()));
    }

    private static NonNullList<ItemStack> copy(NonNullList<ItemStack> source) {
        NonNullList<ItemStack> result = NonNullList.create();
        for (ItemStack stack : source) result.add(stack == null ? ItemStack.EMPTY : stack.copy());
        return result;
    }

    default Optional<RecipeMatch<DistillationRecipeDefinition>> findDistillation(
        FluidVariant input, FluidMatchContext context
    ) {
        return findFluid(DistillationRecipeDefinition.class, RecipeDefinition.Kind.DISTILLATION, input, context);
    }

    default Optional<RecipeMatch<HeatExchangeRecipeDefinition>> findHeating(
        FluidVariant input, FluidMatchContext context
    ) {
        return findFluid(HeatExchangeRecipeDefinition.class, RecipeDefinition.Kind.HEATING, input, context);
    }

    default Optional<RecipeMatch<HeatExchangeRecipeDefinition>> findCooling(
        FluidVariant input, FluidMatchContext context
    ) {
        return findFluid(HeatExchangeRecipeDefinition.class, RecipeDefinition.Kind.COOLING, input, context);
    }

    default <T extends RecipeDefinition> List<RecipeMatch<T>> recipes(Class<T> type) {
        List<RecipeMatch<T>> result = new ArrayList<>();
        for (ResolvedDefinition<RecipeDefinition> resolved : ordered()) {
            if (type.isInstance(resolved.value())) result.add(match(resolved, type.cast(resolved.value())));
        }
        return List.copyOf(result);
    }

    private <T extends RecipeDefinition> Optional<RecipeMatch<T>> findFluid(
        Class<T> type, RecipeDefinition.Kind kind, FluidVariant input, FluidMatchContext context
    ) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(context, "context");
        for (ResolvedDefinition<RecipeDefinition> resolved : ordered()) {
            RecipeDefinition value = resolved.value();
            if (value.kind() != kind || !type.isInstance(value)) continue;
            FluidIngredient ingredient = value instanceof DistillationRecipeDefinition distillation
                ? distillation.input() : ((HeatExchangeRecipeDefinition) value).input();
            if (ingredient.matches(input, context)) return Optional.of(match(resolved, type.cast(value)));
        }
        return Optional.empty();
    }

    private List<ResolvedDefinition<RecipeDefinition>> ordered() {
        List<ResolvedDefinition<RecipeDefinition>> definitions = new ArrayList<>(snapshot().definitions());
        definitions.sort(Comparator
            .<ResolvedDefinition<RecipeDefinition>>comparingInt(v -> v.provenance().priority()).reversed()
            .thenComparing(v -> v.id().toString()));
        return definitions;
    }

    private static <T extends RecipeDefinition> RecipeMatch<T> match(
        ResolvedDefinition<RecipeDefinition> resolved, T recipe
    ) {
        return new RecipeMatch<>(resolved.id(), recipe, resolved.provenance());
    }
}
