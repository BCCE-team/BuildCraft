package buildcraft.lib.recipe;

import com.google.common.collect.ImmutableSet;

import buildcraft.lib.internal.recipes.IngredientStack;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
//? if neoforge && (>=26.3) {
import net.minecraft.advancements.triggers.Criterion;
import net.minecraft.advancements.triggers.RecipeUnlockedTrigger;
//? } else {
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.criterion.RecipeUnlockedTrigger;
//?}
import net.minecraft.data.recipes.RecipeBuilder;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceKey;
//? if neoforge && (>=26.3) {
import net.minecraft.core.registries.Registries;
//? } else {
//?}
import net.minecraft.world.item.crafting.Recipe;

public class AssemblyRecipeBuilder implements RecipeBuilder {
    protected final ItemStack result;
    protected final ImmutableSet<IngredientStack> ingredients;
//? if neoforge && (>=26.3) {
    protected final java.util.Map<String, Criterion<?>> criteria = new java.util.LinkedHashMap<>();
//? } else {
    protected final Advancement.Builder advancement = Advancement.Builder.recipeAdvancement();
//?}
    protected final long requiredMj;
    protected String group;

    public AssemblyRecipeBuilder(long requiredMj, ImmutableSet<IngredientStack> inputs, ItemStack output) {
        this.result = output.copy();
        this.requiredMj = requiredMj;
        this.ingredients = inputs;
    }

    public AssemblyRecipeBuilder unlockedBy(String name, Criterion<?> criterion) {
//? if neoforge && (>=26.3) {
        criteria.put(name, criterion);
//? } else {
        advancement.addCriterion(name, criterion);
//?}
        return this;
    }

    public AssemblyRecipeBuilder group(String group) {
        this.group = group;
        return this;
    }

    public Item getResult() {
        return result.getItem();
    }

//? if neoforge && (>=26.3) {
    public ResourceKey<Recipe<?>> defaultId() {
        return RecipeBuilder.getDefaultRecipeId(result);
    }

//? } else {
//?}
    public void save(RecipeOutput output, ResourceKey<Recipe<?>> key) {
        Identifier id = key.identifier();
//? if neoforge && (>=26.3) {
        Advancement.Builder advancement = output.advancement()
            .addCriterion("has_the_recipe", RecipeUnlockedTrigger.unlocked(output.lookup(Registries.RECIPE).getOrThrow(key)))
//? } else {
        advancement.parent(ROOT_RECIPE_ADVANCEMENT)
            .addCriterion("has_the_recipe", RecipeUnlockedTrigger.unlocked(key))
//?}
            .rewards(AdvancementRewards.Builder.recipe(key))
            .requirements(AdvancementRequirements.Strategy.OR);

//? if neoforge && (>=26.3) {
        criteria.forEach(advancement::addCriterion);
//? } else {
//?}
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
