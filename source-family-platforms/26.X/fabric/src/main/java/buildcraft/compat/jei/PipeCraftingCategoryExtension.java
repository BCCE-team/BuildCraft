//? source if >=1.21.11
package buildcraft.compat.jei;

import java.util.List;
import buildcraft.transport.recipe.PipeRecipe;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/** The same native displays drive the recipe book and JEI's crafting grid. */
final class PipeCraftingCategoryExtension implements ICraftingCategoryExtension<PipeRecipe> {
    static final PipeCraftingCategoryExtension INSTANCE = new PipeCraftingCategoryExtension();

    private PipeCraftingCategoryExtension() {}

    @Override
    public List<SlotDisplay> getIngredients(RecipeHolder<PipeRecipe> holder) {
        return holder.value().placementInfo().ingredients().stream().map(Ingredient::display).toList();
    }

    @Override
    public int getWidth(RecipeHolder<PipeRecipe> holder) {
        return holder.value().hasShapedBasePattern() ? 3 : 0;
    }

    @Override
    public int getHeight(RecipeHolder<PipeRecipe> holder) {
        return holder.value().hasShapedBasePattern() ? 1 : 0;
    }
}
