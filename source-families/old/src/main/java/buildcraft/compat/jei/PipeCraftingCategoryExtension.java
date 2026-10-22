package buildcraft.compat.jei;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import buildcraft.transport.recipe.PipeRecipe;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.ingredient.ICraftingGridHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Gives JEI the shape information that cannot be inferred from {@link PipeRecipe}.
 * BASE recipes are left-middle-right (3x1); upgrade/downgrade recipes are shapeless.
 */
final class PipeCraftingCategoryExtension implements ICraftingCategoryExtension {
    private final PipeRecipe recipe;

    PipeCraftingCategoryExtension(PipeRecipe recipe) {
        this.recipe = recipe;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, ICraftingGridHelper craftingGridHelper, IFocusGroup focuses) {
        craftingGridHelper.createAndSetInputs(builder, ingredientStacks(recipe.getIngredients()), getWidth(), getHeight());
        craftingGridHelper.createAndSetOutputs(builder, List.of(recipe.getDisplayResult()));
    }

    @Override
    public ResourceLocation getRegistryName() {
        return recipe.getId();
    }

    @Override
    public int getWidth() {
        return recipe.hasShapedBasePattern() ? 3 : 0;
    }

    @Override
    public int getHeight() {
        return recipe.hasShapedBasePattern() ? 1 : 0;
    }

    private static List<List<ItemStack>> ingredientStacks(List<Ingredient> ingredients) {
        List<List<ItemStack>> result = new ArrayList<>(ingredients.size());
        for (Ingredient ingredient : ingredients) {
            result.add(Arrays.asList(ingredient.getItems()));
        }
        return result;
    }
}
