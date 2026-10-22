package buildcraft.compat.jei;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import buildcraft.transport.recipe.PipeRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Gives JEI the shape information that cannot be inferred from {@link PipeRecipe}.
 * BASE recipes are left-middle-right (3x1); upgrade/downgrade recipes are shapeless.
 */
final class PipeCraftingCategoryExtension implements ICraftingCategoryExtension<PipeRecipe> {
    static final PipeCraftingCategoryExtension INSTANCE = new PipeCraftingCategoryExtension();

    private PipeCraftingCategoryExtension() {
    }

    @Override
    public void setRecipe(RecipeHolder<PipeRecipe> recipeHolder, IRecipeLayoutBuilder builder,
            ICraftingGridHelper craftingGridHelper, IFocusGroup focuses) {
        PipeRecipe recipe = recipeHolder.value();
        craftingGridHelper.createAndSetInputs(builder, ingredientStacks(recipe.getIngredients()),
                getWidth(recipeHolder), getHeight(recipeHolder));
        craftingGridHelper.createAndSetOutputs(builder, List.of(recipe.getDisplayResult()));
    }

    @Override
    public int getWidth(RecipeHolder<PipeRecipe> recipeHolder) {
        return recipeHolder.value().hasShapedBasePattern() ? 3 : 0;
    }

    @Override
    public int getHeight(RecipeHolder<PipeRecipe> recipeHolder) {
        return recipeHolder.value().hasShapedBasePattern() ? 1 : 0;
    }

    private static List<List<ItemStack>> ingredientStacks(List<Ingredient> ingredients) {
        List<List<ItemStack>> result = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            result.add(Arrays.asList(ingredient.getItems()));
        }
        return result;
    }
}
