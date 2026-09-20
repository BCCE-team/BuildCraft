//? source if >=1.21.11
package buildcraft.lib.gui.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

/**
 * Native 1.21.11 recipe-book panel for BuildCraft phantom crafting grids.
 *
 * <p>On 1.21.11 the panel consumes public ClientRecipeBook display entries and forwards the selected
 * RecipeDisplay to the machine GUI, avoiding private RecipeBookComponent state for phantom grids.</p>
 */
public final class GuiRecipeBookPhantom implements buildcraft.lib.compat.minecraft.gui.BCWidgetInput {
    private static final int PANEL_WIDTH = 142;
    private static final int PANEL_HEIGHT = 148;
    private static final int GRID_X = 9;
    private static final int GRID_Y = 24;
    private static final int GRID_COLUMNS = 5;

    public final Consumer<RecipeDisplay> recipeSetter;

    private final RecipeBookPagePhantom page = new RecipeBookPagePhantom();
    private final List<GuiButtonRecipePhantom> buttons = new ArrayList<>();

    private Minecraft minecraft;
    private ContextMap displayContext;
    private boolean visible;
    private boolean narrow;
    private int screenWidth;
    private int screenHeight;
    private int panelX;
    private int panelY;
    private int refreshTicker;

    public GuiRecipeBookPhantom(Consumer<RecipeDisplay> recipeSetter) {
        this.recipeSetter = recipeSetter;
    }

    public void init(int width, int height, Minecraft minecraft, boolean narrow) {
        this.minecraft = minecraft;
        this.narrow = narrow;
        this.screenWidth = width;
        this.screenHeight = height;
        this.displayContext = minecraft.level == null ? null : SlotDisplayContext.fromLevel(minecraft.level);
        rebuildRecipes();
        updatePanelPosition((width - 176) / 2);
    }

    public int updateScreenPosition(int width, int imageWidth) {
        this.screenWidth = width;
        int left = (width - imageWidth) / 2;
        if (visible && !narrow) {
            left += 76;
        }
        updatePanelPosition(left);
        return left;
    }

    private void updatePanelPosition(int guiLeft) {
        panelX = narrow ? (screenWidth - PANEL_WIDTH) / 2 : guiLeft - PANEL_WIDTH - 4;
        panelY = (screenHeight - PANEL_HEIGHT) / 2;
        rebuildButtons();
    }

    public void tick() {
        if (++refreshTicker >= 20) {
            refreshTicker = 0;
            rebuildRecipes();
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (!visible || minecraft == null) return;

        graphics.nextStratum();
        graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xF0101010);
        graphics.renderOutline(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 0xFF808080);
        graphics.drawString(minecraft.font, Component.translatable("gui.recipebook.toggleRecipes"),
            panelX + 8, panelY + 8, 0xFFFFFFFF, false);

        for (GuiButtonRecipePhantom button : buttons) {
            button.render(graphics, button.contains(mouseX, mouseY));
        }

        String pageText = (page.page() + 1) + "/" + page.pageCount();
        graphics.drawCenteredString(minecraft.font, pageText, panelX + PANEL_WIDTH / 2, panelY + 132, 0xFFFFFFFF);
        graphics.drawString(minecraft.font, "<", panelX + 12, panelY + 131, page.page() > 0 ? 0xFFFFFFFF : 0xFF606060, false);
        graphics.drawString(minecraft.font, ">", panelX + PANEL_WIDTH - 17, panelY + 131,
            page.page() + 1 < page.pageCount() ? 0xFFFFFFFF : 0xFF606060, false);
    }

    public void renderGhostRecipe(GuiGraphics graphics, int leftPos, int topPos, boolean big, float partialTicks) {
        // Selecting a recipe immediately fills the real BuildCraft phantom slots, so a separate vanilla ghost grid
        // would only duplicate the same visual information.
    }

    public void renderTooltip(GuiGraphics graphics, int leftPos, int topPos, int mouseX, int mouseY) {
        if (!visible || minecraft == null) return;
        for (GuiButtonRecipePhantom button : buttons) {
            if (button.contains(mouseX, mouseY) && !button.result().isEmpty()) {
                graphics.setTooltipForNextFrame(minecraft.font, button.result(), mouseX, mouseY);
                return;
            }
        }
    }

    public void toggleVisibility() {
        visible = !visible;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (!visible) return false;
        if (mouseButton == 0) {
            for (GuiButtonRecipePhantom button : buttons) {
                if (button.contains(mouseX, mouseY)) {
                    recipeSetter.accept(button.entry().display());
                    return true;
                }
            }
            if (mouseY >= panelY + 125 && mouseY < panelY + 146) {
                if (mouseX >= panelX + 4 && mouseX < panelX + 32) {
                    if (page.previous()) rebuildButtons();
                    return true;
                }
                if (mouseX >= panelX + PANEL_WIDTH - 32 && mouseX < panelX + PANEL_WIDTH - 4) {
                    if (page.next()) rebuildButtons();
                    return true;
                }
            }
        }
        return isInside(mouseX, mouseY);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    public void slotClicked(Slot slot) {
    }

    public boolean hasClickedOutside(double mouseX, double mouseY, int leftPos, int topPos, int imageWidth,
        int imageHeight) {
        return !visible || !isInside(mouseX, mouseY);
    }

    public void recipesUpdated() {
        rebuildRecipes();
    }

    /** Resolves a 1.21.11 crafting display into the nine phantom stacks used by BuildCraft machines. */
    public static List<ItemStack> resolveCraftingGrid(RecipeDisplay display, Level level) {
        List<ItemStack> stacks = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) stacks.add(ItemStack.EMPTY);
        if (level == null) return stacks;

        ContextMap context = SlotDisplayContext.fromLevel(level);
        List<SlotDisplay> ingredients;
        int recipeWidth;
        int recipeHeight;

        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            ingredients = shaped.ingredients();
            recipeWidth = Math.min(3, shaped.width());
            recipeHeight = Math.min(3, shaped.height());
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            ingredients = shapeless.ingredients();
            recipeWidth = Math.min(3, Math.max(1, ingredients.size()));
            recipeHeight = Math.min(3, (ingredients.size() + 2) / 3);
            // Shapeless recipes have no meaningful geometry. Keep the normal 3-wide packing for 2+ items,
            // but centre a single ingredient like the vanilla crafting-book placement.
            if (ingredients.size() > 1) recipeWidth = 3;
        } else {
            return stacks;
        }

        int offsetX = recipeWidth == 1 ? 1 : 0;
        int offsetY = recipeHeight == 1 ? 1 : 0;
        for (int y = 0; y < recipeHeight; y++) {
            for (int x = 0; x < recipeWidth; x++) {
                int ingredientIndex = x + y * recipeWidth;
                if (ingredientIndex >= ingredients.size()) break;
                ItemStack stack = ingredients.get(ingredientIndex).resolveForFirstStack(context);
                stacks.set((x + offsetX) + (y + offsetY) * 3, stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
            }
        }
        return stacks;
    }

    private boolean isInside(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX < panelX + PANEL_WIDTH && mouseY >= panelY && mouseY < panelY + PANEL_HEIGHT;
    }

    private void rebuildRecipes() {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            page.setEntries(List.of());
            buttons.clear();
            return;
        }
        displayContext = SlotDisplayContext.fromLevel(minecraft.level);
        page.setEntries(RecipeListPhantom.from(minecraft.player.getRecipeBook()).entries());
        rebuildButtons();
    }

    private void rebuildButtons() {
        buttons.clear();
        if (displayContext == null) return;
        List<net.minecraft.world.item.crafting.display.RecipeDisplayEntry> entries = page.visibleEntries();
        for (int i = 0; i < entries.size(); i++) {
            int x = panelX + GRID_X + (i % GRID_COLUMNS) * 25;
            int y = panelY + GRID_Y + (i / GRID_COLUMNS) * 25;
            buttons.add(new GuiButtonRecipePhantom(entries.get(i), displayContext, x, y));
        }
    }
}
