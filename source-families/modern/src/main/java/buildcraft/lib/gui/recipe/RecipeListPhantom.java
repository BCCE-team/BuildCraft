//? source if >=1.21.1
package buildcraft.lib.gui.recipe;

import java.util.List;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import buildcraft.lib.compat.minecraft.recipe.BCRecipeDisplays;

/** Client-side flattened recipe-display list used by BuildCraft guide/phantom recipes on 1.21.11. */
public final class RecipeListPhantom {
    private final List<RecipeDisplayEntry> entries;

    private RecipeListPhantom(List<RecipeDisplayEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static RecipeListPhantom from(ClientRecipeBook recipeBook) {
        return new RecipeListPhantom(
            recipeBook == null ? List.of() : BCRecipeDisplays.unlockedCrafting(recipeBook)
        );
    }

    public List<RecipeDisplayEntry> entries() {
        return entries;
    }
}
