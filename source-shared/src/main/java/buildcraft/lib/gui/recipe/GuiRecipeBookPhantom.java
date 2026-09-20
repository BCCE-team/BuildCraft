package buildcraft.lib.gui.recipe;

import java.lang.reflect.Field;
import java.util.function.Consumer;

import buildcraft.lib.gui.slot.SlotPhantom;
import buildcraft.lib.tile.item.ItemHandlerManager;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import net.minecraft.client.gui.components.StateSwitchingButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookPage;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;

/** A {@link RecipeBookComponent} that can always craft things, as it places the required items into phantom slots
 * ({@link SlotPhantom} or {@link ItemHandlerManager} with {@link EnumAccess#PHANTOM}). */
public class GuiRecipeBookPhantom extends RecipeBookComponent {
    public final Consumer<CraftingRecipe> recipeSetter;
    private final Field stackedContentsField;

    public GuiRecipeBookPhantom(Consumer<CraftingRecipe> recipeSetter) throws ReflectiveOperationException {
        this.recipeSetter = recipeSetter;
        replaceFieldByType(RecipeBookPage.class, new RecipeBookPagePhantom(this));
        stackedContentsField = findFieldByType(StackedContents.class);
    }

    private static Field findFieldByType(Class<?> type) throws NoSuchFieldException {
        for (Field field : RecipeBookComponent.class.getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException("No RecipeBookComponent field of type " + type.getName());
    }

    private void replaceFieldByType(Class<?> type, Object value) throws ReflectiveOperationException {
        findFieldByType(type).set(this, value);
    }

    /** Compatibility helper for screens that populate a phantom crafting container. */
    public void initVisuals(boolean ignored, CraftingContainer craftingContainer) {
        initVisuals();
        try {
            StackedContents stackedContents = (StackedContents) stackedContentsField.get(this);
            craftingContainer.fillStackedContents(stackedContents);
            recipesUpdated();
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Unable to update phantom recipe book contents", exception);
        }
    }

    @Override
    public void initVisuals() {
        super.initVisuals();
        StateSwitchingButton button = this.filterButton;
        button.setX(-100000);
        button.setY(-100000);
    }
}
