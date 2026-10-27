package buildcraft.api.v2.recipe;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/** Fixed-result Integration Table recipe loaded from a datapack. No arbitrary Java code is parsed from JSON. */
public final class StaticIntegrationRecipe implements IntegrationRecipeDefinition {
    private final CountedIngredient center;
    private final List<CountedIngredient> ingredients;
    private final ItemStack result;
    private final long power;

    public StaticIntegrationRecipe(CountedIngredient center, List<CountedIngredient> ingredients,
                                   ItemStack result, long requiredMicroJoules) {
        this.center = Objects.requireNonNull(center, "center");
        this.ingredients = List.copyOf(Objects.requireNonNull(ingredients, "ingredients"));
        if (this.ingredients.isEmpty() || this.ingredients.size() > 8) {
            throw new IllegalArgumentException("Integration needs 1 to 8 additional ingredients");
        }
        this.result = Objects.requireNonNull(result, "result").copy();
        if (this.result.isEmpty()) throw new IllegalArgumentException("Integration result is empty");
        if (requiredMicroJoules <= 0) throw new IllegalArgumentException("Integration energy must be positive");
        this.power = requiredMicroJoules;
    }

    @Override public ItemStack output(ItemStack target, NonNullList<ItemStack> toIntegrate) {
        if (!center.test(target) || toIntegrate == null || !matches(toIntegrate)) return ItemStack.EMPTY;
        return result.copy();
    }

    private boolean matches(NonNullList<ItemStack> supplied) {
        var remaining = AssemblyIngredients.remaining(ingredients, supplied);
        return remaining.isPresent() && remaining.orElseThrow().stream().allMatch(ItemStack::isEmpty);
    }

    @Override public List<CountedIngredient> requirements(ItemStack output) {
        return sameOutput(output) ? ingredients : List.of();
    }

    @Override public long requiredMicroJoules(ItemStack output) { return sameOutput(output) ? power : 0; }
    @Override public CountedIngredient centerIngredient() { return center; }

    /** A defensive copy of the fixed output, for recipe book and JEI displays. */
    public ItemStack result() { return result.copy(); }

    private boolean sameOutput(ItemStack stack) {
        return stack != null && !stack.isEmpty() && result.getCount() == stack.getCount()
            && CountedIngredient.sameItemAndData(result, stack);
    }
}
