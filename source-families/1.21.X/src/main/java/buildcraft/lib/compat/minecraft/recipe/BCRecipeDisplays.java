//? source if >=1.21.11
package buildcraft.lib.compat.minecraft.recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;

/** Native recipe-book access. Keep recipe eligibility/gameplay policy in its existing consumer. */
public final class BCRecipeDisplays {
    private BCRecipeDisplays() {}
    public static List<RecipeDisplayEntry> unlockedCrafting(ClientRecipeBook book) {
        Map<Object, RecipeDisplayEntry> unique = new LinkedHashMap<>();
        for (RecipeCollection collection : book.getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (entry.display() instanceof ShapedCraftingRecipeDisplay
                    || entry.display() instanceof ShapelessCraftingRecipeDisplay) {
                    unique.putIfAbsent(entry.id(), entry);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }
    public static List<SlotDisplay> craftingInputs(RecipeDisplayEntry entry) {
        RecipeDisplay display = entry.display();
        if (display instanceof ShapedCraftingRecipeDisplay shaped) return shaped.ingredients();
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients();
        return List.of();
    }
}
