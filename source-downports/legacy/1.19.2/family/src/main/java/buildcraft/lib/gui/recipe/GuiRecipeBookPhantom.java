package buildcraft.lib.gui.recipe;

import java.lang.reflect.Field;
import java.util.function.Consumer;

import buildcraft.lib.gui.slot.SlotPhantom;
import buildcraft.lib.tile.item.ItemHandlerManager;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.world.item.crafting.Recipe;

/** A {@link RecipeBookComponent} that writes the selected recipe into phantom slots (either
 * {@link SlotPhantom} or an {@link ItemHandlerManager} inventory with {@link EnumAccess#PHANTOM}). */
public class GuiRecipeBookPhantom extends RecipeBookComponent {

    private static final Field FIELD_GUI_BOOK;

    public final Consumer<Recipe<?>> recipeSetter;

    // RecipeBookComponent does not expose its page, so replace only that field. Everything else, including the
    // craftable-only toggle and its persisted vanilla state, remains standard.
    static {
        try {
            Field recipePage = null;
            for (Field field : RecipeBookComponent.class.getDeclaredFields()) {
                if (field.getType() == RecipeBookPage.class) {
                    if (recipePage != null) {
                        throw new IllegalStateException("Found multiple RecipeBookPage fields");
                    }
                    recipePage = field;
                }
            }
            if (recipePage == null) {
                throw new IllegalStateException("Could not find the RecipeBookPage field");
            }
            recipePage.setAccessible(true);
            FIELD_GUI_BOOK = recipePage;
        } catch (Throwable error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    public GuiRecipeBookPhantom(Consumer<Recipe<?>> recipeSetter) throws ReflectiveOperationException {
        this.recipeSetter = recipeSetter;
        FIELD_GUI_BOOK.set(this, new RecipeBookPagePhantom(this));
    }
}
