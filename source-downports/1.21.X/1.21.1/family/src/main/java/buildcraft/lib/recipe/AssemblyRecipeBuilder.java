package buildcraft.lib.recipe;

import com.google.common.collect.ImmutableSet;

import buildcraft.lib.internal.recipes.IngredientStack;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.RecipeUnlockedTrigger;
import net.minecraft.data.recipes.RecipeBuilder;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class AssemblyRecipeBuilder implements RecipeBuilder {
    protected final ItemStack result;
    protected final ImmutableSet<IngredientStack> ingredients;
    protected final Advancement.Builder advancement = Advancement.Builder.recipeAdvancement();
    protected final long requiredMj;
    protected String group;

    public AssemblyRecipeBuilder(long requiredMj, ImmutableSet<IngredientStack> inputs, ItemStack output) {
        this.result = output.copy();
        this.requiredMj = requiredMj;
        this.ingredients = inputs;
    }

    @Override
    public AssemblyRecipeBuilder unlockedBy(String name, Criterion<?> criterion) {
        advancement.addCriterion(name, criterion);
        return this;
    }

    @Override
    public AssemblyRecipeBuilder group(String group) {
        this.group = group;
        return this;
    }

    @Override
    public Item getResult() {
        return result.getItem();
    }

    @Override
    public void save(RecipeOutput output, ResourceLocation id) {
        advancement.parent(ROOT_RECIPE_ADVANCEMENT)
            .addCriterion("has_the_recipe", RecipeUnlockedTrigger.unlocked(id))
            .rewards(AdvancementRewards.Builder.recipe(id))
            .requirements(AdvancementRequirements.Strategy.OR);

        AdvancementHolder advancementHolder = advancement.build(
            ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "recipes/" + id.getPath())
        );
        output.accept(
            id,
            new AssemblyRecipe(id, requiredMj, ingredients, result, group == null ? "" : group),
            advancementHolder
        );
    }
}
