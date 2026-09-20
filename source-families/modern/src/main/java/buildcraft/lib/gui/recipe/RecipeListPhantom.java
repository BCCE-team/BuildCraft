//? source if >=1.21.1
package buildcraft.lib.gui.recipe;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;

/** Client-side flattened recipe-display list used by BuildCraft guide/phantom recipes on 1.21.11. */
public final class RecipeListPhantom {
    private final List<RecipeDisplayEntry> entries;

    private RecipeListPhantom(List<RecipeDisplayEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public static RecipeListPhantom from(ClientRecipeBook recipeBook) {
        List<RecipeDisplayEntry> entries = new ArrayList<>();
        if (recipeBook != null) {
            for (RecipeCollection collection : recipeBook.getCollections()) {
                entries.addAll(collection.getRecipes());
            }
        }
        return new RecipeListPhantom(entries);
    }

    public List<RecipeDisplayEntry> entries() {
        return entries;
    }
}
