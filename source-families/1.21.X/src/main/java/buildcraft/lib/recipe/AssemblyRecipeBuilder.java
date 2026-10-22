//? source if >=1.21.1
package buildcraft.lib.recipe;

import com.google.common.collect.ImmutableSet;

import buildcraft.lib.internal.recipes.IngredientStack;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.criterion.RecipeUnlockedTrigger;
import net.minecraft.data.recipes.RecipeBuilder;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;

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

    public AssemblyRecipeBuilder unlockedBy(String name, Criterion<?> criterion) {
        advancement.addCriterion(name, criterion);
        return this;
    }

    public AssemblyRecipeBuilder group(String group) {
        this.group = group;
        return this;
    }

    public Item getResult() {
        return result.getItem();
    }

    public void save(RecipeOutput output, ResourceKey<Recipe<?>> key) {
        Identifier id = key.identifier();
        advancement.parent(ROOT_RECIPE_ADVANCEMENT)
            .addCriterion("has_the_recipe", RecipeUnlockedTrigger.unlocked(key))
            .rewards(AdvancementRewards.Builder.recipe(key))
            .requirements(AdvancementRequirements.Strategy.OR);

        AdvancementHolder advancementHolder = advancement.build(
            Identifier.fromNamespaceAndPath(id.getNamespace(), "recipes/" + id.getPath())
        );
        output.accept(
            key,
            new AssemblyRecipe(id, requiredMj, ingredients, result, group == null ? "" : group),
            advancementHolder
        );
    }
}
