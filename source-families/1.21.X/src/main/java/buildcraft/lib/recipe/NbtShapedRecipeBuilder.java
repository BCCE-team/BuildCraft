//? source if >=1.21.1
package buildcraft.lib.recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.criterion.RecipeUnlockedTrigger;
import net.minecraft.data.recipes.RecipeBuilder;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.ItemLike;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import buildcraft.lib.compat.IngredientCompat;

/**
 * Shaped recipe builder that keeps the complete output stack, including data
 * components. Vanilla's 1.21.1 {@code ShapedRecipeBuilder} accepts only an
 * {@link ItemLike}, so it cannot represent BuildCraft gate variants stored on
 * the result stack.
 */
public class NbtShapedRecipeBuilder implements RecipeBuilder {
    private final ItemStack result;
    private final CompoundTag nbt;
    private final List<String> rows = new ArrayList<>();
    private final Map<Character, Ingredient> key = new LinkedHashMap<>();
    private final Advancement.Builder advancement = Advancement.Builder.recipeAdvancement();
    private String group = "";
    private boolean showNotification = true;

    public NbtShapedRecipeBuilder(ItemStack stack) {
        this.result = stack.copy();
        this.nbt = ItemStackUtil.getCustomData(stack);
    }

    public NbtShapedRecipeBuilder(ItemLike item) {
        this(new ItemStack(item));
    }

    public NbtShapedRecipeBuilder(ItemLike item, int count) {
        this(new ItemStack(item, count));
    }

    public CompoundTag getTag() {
        return nbt.copy();
    }

    public NbtShapedRecipeBuilder pattern(String row) {
        if (!rows.isEmpty() && row.length() != rows.get(0).length()) {
            throw new IllegalArgumentException("Pattern rows must have the same width");
        }
        rows.add(row);
        return this;
    }

    public NbtShapedRecipeBuilder define(char symbol, TagKey<Item> tag) {
        return define(symbol, IngredientCompat.of(tag));
    }

    public NbtShapedRecipeBuilder define(char symbol, ItemLike item) {
        return define(symbol, IngredientCompat.of(item));
    }

    public NbtShapedRecipeBuilder define(char symbol, Ingredient ingredient) {
        if (symbol == ' ') {
            throw new IllegalArgumentException("Space is reserved for empty recipe slots");
        }
        if (key.putIfAbsent(symbol, ingredient) != null) {
            throw new IllegalArgumentException("Symbol '" + symbol + "' is already defined");
        }
        return this;
    }

    public NbtShapedRecipeBuilder unlockedBy(String name, Criterion<?> criterion) {
        advancement.addCriterion(name, criterion);
        return this;
    }

    public NbtShapedRecipeBuilder group(String group) {
        this.group = group == null ? "" : group;
        return this;
    }

    public NbtShapedRecipeBuilder showNotification(boolean showNotification) {
        this.showNotification = showNotification;
        return this;
    }

    public Item getResult() {
        return result.getItem();
    }

    public void save(RecipeOutput output, ResourceKey<Recipe<?>> key) {
        Identifier id = key.identifier();
        if (rows.isEmpty()) {
            throw new IllegalStateException("No pattern is defined for recipe " + id);
        }

        ShapedRecipePattern pattern = ShapedRecipePattern.of(this.key, rows);
        CraftingBookCategory bookCategory = RecipeBuilder.determineBookCategory(RecipeCategory.MISC);
        ShapedRecipe recipe = new ShapedRecipe(group, bookCategory, pattern, result.copy(), showNotification);

        advancement.parent(ROOT_RECIPE_ADVANCEMENT)
            .addCriterion("has_the_recipe", RecipeUnlockedTrigger.unlocked(key))
            .rewards(AdvancementRewards.Builder.recipe(key))
            .requirements(AdvancementRequirements.Strategy.OR);

        AdvancementHolder advancementHolder = advancement.build(
            Identifier.fromNamespaceAndPath(id.getNamespace(), "recipes/" + id.getPath())
        );
        output.accept(key, recipe, advancementHolder);
    }
}
