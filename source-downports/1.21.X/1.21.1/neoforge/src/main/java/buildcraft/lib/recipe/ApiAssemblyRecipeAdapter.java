package buildcraft.lib.recipe;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.recipe.AssemblyIngredients;
import buildcraft.api.v2.recipe.AssemblyRecipeDefinition;
import buildcraft.api.v2.recipe.CountedIngredient;
import buildcraft.lib.internal.recipes.IngredientStack;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Internal bridge from public API 2 recipes to the existing Assembly Table runtime and GUI protocol. */
public final class ApiAssemblyRecipeAdapter extends AssemblyRecipe {
    private final ResourceLocation id;

    private ApiAssemblyRecipeAdapter(ResourceLocation id) {
        // This adapter is not a Minecraft datapack recipe. All machine methods are delegated.
        super(id, 0L, ImmutableSet.of(), new ItemStack(Items.PAPER), "");
        this.id = Objects.requireNonNull(id, "id");
    }

    /** This may return null when a datapack reload removed the definition. */
    private AssemblyRecipeDefinition current() {
        return BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES)
            .assemblyById(id).map(match -> match.recipe()).orElse(null);
    }

    public static ApiAssemblyRecipeAdapter fromService(ResourceLocation id) {
        return BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES).assemblyById(id).isPresent()
            ? new ApiAssemblyRecipeAdapter(id) : null;
    }

    public boolean registered() { return current() != null; }

    /** Client view of a server-authoritative recipe id; never used for server-side crafting. */
    public static ApiAssemblyRecipeAdapter synced(ResourceLocation id) { return new ApiAssemblyRecipeAdapter(id); }

    private static NonNullList<ItemStack> snapshot(IItemHandlerModifiable inv) {
        NonNullList<ItemStack> result = NonNullList.create();
        for (int slot = 0; slot < inv.getSlots(); slot++) result.add(inv.getStackInSlot(slot).copy());
        return result;
    }

    private static Set<ItemStack> safeCopies(Set<ItemStack> stacks) {
        Set<ItemStack> result = new LinkedHashSet<>();
        if (stacks != null) for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) result.add(stack.copy());
        }
        return result;
    }

    @Override public ItemStack getResultItem(net.minecraft.core.HolderLookup.Provider registries) {
        return getOutputPreviews().stream().findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    @Override public Set<ItemStack> getOutputs(IItemHandlerModifiable inv) {
        AssemblyRecipeDefinition definition = current();
        if (definition == null) return Set.of();
        try { return safeCopies(definition.outputs(snapshot(inv))); }
        catch (RuntimeException invalid) { return Set.of(); }
    }

    @Override public Set<ItemStack> getOutputPreviews() {
        AssemblyRecipeDefinition definition = current();
        if (definition == null) return Set.of();
        try { return safeCopies(definition.outputPreviews()); }
        catch (RuntimeException invalid) { return Set.of(); }
    }

    @Override public Set<IngredientStack> getInputsFor(ItemStack output) {
        AssemblyRecipeDefinition definition = current();
        if (definition == null) return Set.of();
        Set<IngredientStack> result = new LinkedHashSet<>();
        try {
            for (CountedIngredient ingredient : definition.requirements(output.copy())) {
                result.add(new IngredientStack(ingredient.ingredient(), ingredient.count()));
            }
        } catch (RuntimeException invalid) { return Set.of(); }
        return result;
    }

    @Override public long getRequiredMicroJoulesFor(ItemStack output) {
        AssemblyRecipeDefinition definition = current();
        if (definition == null) return 0;
        try { return Math.max(0, definition.requiredMicroJoules(output.copy())); }
        catch (RuntimeException invalid) { return 0; }
    }

    /** Atomic item extraction for API recipes: exact components and overlapping tags are preserved. */
    public boolean consume(IItemHandlerModifiable inv, ItemStack output, boolean simulate) {
        AssemblyRecipeDefinition definition = current();
        if (definition == null || output == null || output.isEmpty()) return false;
        try {
            NonNullList<ItemStack> stacks = snapshot(inv);
            boolean produces = safeCopies(definition.outputs(stacks)).stream()
                .anyMatch(candidate -> candidate.getCount() == output.getCount()
                    && CountedIngredient.sameItemAndData(candidate, output));
            if (!produces || definition.requiredMicroJoules(output.copy()) <= 0) return false;
            Optional<List<ItemStack>> remaining = AssemblyIngredients.remaining(
                definition.requirements(output.copy()), stacks);
            if (remaining.isEmpty()) return false;
            if (!simulate) {
                for (int slot = 0; slot < inv.getSlots(); slot++) {
                    inv.setStackInSlot(slot, remaining.orElseThrow().get(slot));
                }
            }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }
}
