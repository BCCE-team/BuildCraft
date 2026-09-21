//? source if >=1.21.11
package buildcraft.lib.gui.recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;

import buildcraft.lib.compat.minecraft.gui.BCGuiInput;

/**
 * Recipe-book panel for BuildCraft phantom crafting grids on 1.21.11.
 *
 * <p>Minecraft no longer exposes the old client recipe-manager path used by the previous
 * {@code RecipeBookComponent} integration. This panel keeps the same player-facing contract on top of the public
 * {@code ClientRecipeBook}: crafting-only entries, search, category tabs, keyboard input, vanilla-sized 20-entry
 * pages and deterministic phantom placement.</p>
 */
public final class GuiRecipeBookPhantom implements buildcraft.lib.compat.minecraft.gui.BCWidgetInput {
    private static final int PANEL_WIDTH = 142;
    private static final int PANEL_HEIGHT = 148;
    private static final int GRID_X = 9;
    private static final int GRID_Y = 24;
    private static final int GRID_COLUMNS = 5;
    private static final int CATEGORY_SIZE = 24;

    public final Consumer<RecipeDisplay> recipeSetter;

    private final RecipeBookPagePhantom page = new RecipeBookPagePhantom();
    private final List<GuiButtonRecipePhantom> buttons = new ArrayList<>();
    private final List<CategoryTab> categoryTabs = new ArrayList<>();

    private Minecraft minecraft;
    private ContextMap displayContext;
    private EditBox searchBox;
    private List<RecipeDisplayEntry> allEntries = List.of();
    private Object selectedCategory;
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
        String previousSearch = searchBox == null ? "" : searchBox.getValue();
        this.minecraft = minecraft;
        this.narrow = narrow;
        this.screenWidth = width;
        this.screenHeight = height;
        this.displayContext = minecraft.level == null ? null : SlotDisplayContext.fromLevel(minecraft.level);
        updatePanelPosition((width - 176) / 2);

        searchBox = new EditBox(minecraft.font, panelX + 26, panelY + 6, 108, 14,
            Component.translatable("gui.recipebook.search_hint"));
        searchBox.setMaxLength(50);
        searchBox.setValue(previousSearch);
        searchBox.setResponder(value -> applyFilters(true));

        rebuildRecipes(false);
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
        if (searchBox != null) {
            searchBox.setPosition(panelX + 26, panelY + 6);
        }
        rebuildButtons();
        rebuildCategoryTabs();
    }

    public void tick() {
        if (++refreshTicker >= 20) {
            refreshTicker = 0;
            rebuildRecipes(false);
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
        graphics.drawString(minecraft.font, Component.literal("\u2315"), panelX + 9, panelY + 9, 0xFFA0A0A0, false);
        if (searchBox != null) {
            searchBox.render(graphics, mouseX, mouseY, partialTicks);
        }

        for (CategoryTab tab : categoryTabs) {
            boolean hovered = tab.contains(mouseX, mouseY);
            boolean selected = Objects.equals(selectedCategory, tab.category());
            int background = selected ? 0xFF8A8A8A : hovered ? 0xFF707070 : 0xFF4A4A4A;
            graphics.fill(tab.x(), tab.y(), tab.x() + CATEGORY_SIZE, tab.y() + CATEGORY_SIZE, background);
            graphics.fill(tab.x() + 1, tab.y() + 1, tab.x() + CATEGORY_SIZE - 1, tab.y() + CATEGORY_SIZE - 1,
                0xFF202020);
            if (!tab.icon().isEmpty()) {
                graphics.renderFakeItem(tab.icon(), tab.x() + 4, tab.y() + 4);
            }
        }

        for (GuiButtonRecipePhantom button : buttons) {
            button.render(graphics, button.contains(mouseX, mouseY));
        }

        String pageText = (page.page() + 1) + "/" + page.pageCount();
        graphics.drawCenteredString(minecraft.font, pageText, panelX + PANEL_WIDTH / 2, panelY + 132, 0xFFFFFFFF);
        graphics.drawString(minecraft.font, "<", panelX + 12, panelY + 131,
            page.page() > 0 ? 0xFFFFFFFF : 0xFF606060, false);
        graphics.drawString(minecraft.font, ">", panelX + PANEL_WIDTH - 17, panelY + 131,
            page.page() + 1 < page.pageCount() ? 0xFFFFFFFF : 0xFF606060, false);
    }

    public void renderGhostRecipe(GuiGraphics graphics, int leftPos, int topPos, boolean big, float partialTicks) {
        // Selecting a recipe fills the BuildCraft phantom slots directly.
    }

    public void renderTooltip(GuiGraphics graphics, int leftPos, int topPos, int mouseX, int mouseY) {
        if (!visible || minecraft == null) return;
        for (CategoryTab tab : categoryTabs) {
            if (tab.contains(mouseX, mouseY) && !tab.icon().isEmpty()) {
                graphics.setTooltipForNextFrame(minecraft.font, tab.icon(), mouseX, mouseY);
                return;
            }
        }
        for (GuiButtonRecipePhantom button : buttons) {
            if (button.contains(mouseX, mouseY) && !button.result().isEmpty()) {
                graphics.setTooltipForNextFrame(minecraft.font, button.result(), mouseX, mouseY);
                return;
            }
        }
    }

    public void toggleVisibility() {
        visible = !visible;
        if (!visible && searchBox != null) {
            searchBox.setFocused(false);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (!visible) return false;
        if (mouseButton == 0) {
            for (CategoryTab tab : categoryTabs) {
                if (tab.contains(mouseX, mouseY)) {
                    selectedCategory = tab.category();
                    applyFilters(true);
                    return true;
                }
            }
            if (searchBox != null && BCGuiInput.click(searchBox, mouseX, mouseY, mouseButton)) {
                return true;
            }
            if (searchBox != null) {
                searchBox.setFocused(false);
            }
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
        return isInside(mouseX, mouseY) || isInsideCategories(mouseX, mouseY);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return visible && searchBox != null && BCGuiInput.key(searchBox, keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return visible && searchBox != null && BCGuiInput.character(searchBox, codePoint, modifiers);
    }

    public void slotClicked(Slot slot) {
    }

    public boolean hasClickedOutside(double mouseX, double mouseY, int leftPos, int topPos, int imageWidth,
        int imageHeight) {
        return !visible || (!isInside(mouseX, mouseY) && !isInsideCategories(mouseX, mouseY));
    }

    public void recipesUpdated() {
        rebuildRecipes(false);
    }

    /** Resolves a 1.21.11 crafting display into the nine phantom stacks used by BuildCraft machines. */
    public static Optional<List<ItemStack>> resolveCraftingGrid(RecipeDisplay display, Level level) {
        if (level == null) return Optional.empty();
        List<ItemStack> stacks = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) stacks.add(ItemStack.EMPTY);

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
            if (ingredients.size() > 1) recipeWidth = 3;
        } else {
            return Optional.empty();
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
        return Optional.of(stacks);
    }

    private boolean isInside(double mouseX, double mouseY) {
        return mouseX >= panelX && mouseX < panelX + PANEL_WIDTH && mouseY >= panelY && mouseY < panelY + PANEL_HEIGHT;
    }

    private boolean isInsideCategories(double mouseX, double mouseY) {
        for (CategoryTab tab : categoryTabs) {
            if (tab.contains(mouseX, mouseY)) return true;
        }
        return false;
    }

    private void rebuildRecipes(boolean resetPage) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            allEntries = List.of();
            page.setEntries(List.of());
            buttons.clear();
            categoryTabs.clear();
            return;
        }
        displayContext = SlotDisplayContext.fromLevel(minecraft.level);
        allEntries = RecipeListPhantom.from(minecraft.player.getRecipeBook()).entries();
        rebuildCategoryTabs();
        if (selectedCategory != null && allEntries.stream().noneMatch(entry -> Objects.equals(entry.category(), selectedCategory))) {
            selectedCategory = null;
        }
        applyFilters(resetPage);
    }

    private void applyFilters(boolean resetPage) {
        if (displayContext == null) return;
        String query = searchBox == null ? "" : searchBox.getValue().strip().toLowerCase(Locale.ROOT);
        List<RecipeDisplayEntry> filtered = new ArrayList<>();
        for (RecipeDisplayEntry entry : allEntries) {
            if (selectedCategory != null && !Objects.equals(selectedCategory, entry.category())) {
                continue;
            }
            if (!query.isEmpty() && !matchesSearch(entry, query)) {
                continue;
            }
            filtered.add(entry);
        }
        if (resetPage) page.reset();
        page.setEntries(filtered);
        rebuildButtons();
    }

    private boolean matchesSearch(RecipeDisplayEntry entry, String query) {
        if (displayContext == null) return true;
        for (ItemStack stack : entry.resultItems(displayContext)) {
            if (!stack.isEmpty() && stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }

    private void rebuildCategoryTabs() {
        categoryTabs.clear();
        if (displayContext == null) return;

        categoryTabs.add(new CategoryTab(null, new ItemStack(Items.CRAFTING_TABLE), panelX - CATEGORY_SIZE - 2, panelY + 8));
        Map<Object, ItemStack> categories = new LinkedHashMap<>();
        for (RecipeDisplayEntry entry : allEntries) {
            categories.computeIfAbsent(entry.category(), ignored -> entry.resultItems(displayContext).stream()
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY));
        }
        int index = 1;
        for (Map.Entry<Object, ItemStack> entry : categories.entrySet()) {
            categoryTabs.add(new CategoryTab(entry.getKey(), entry.getValue(), panelX - CATEGORY_SIZE - 2,
                panelY + 8 + index * 27));
            index++;
        }
    }

    private void rebuildButtons() {
        buttons.clear();
        if (displayContext == null) return;
        List<RecipeDisplayEntry> entries = page.visibleEntries();
        for (int i = 0; i < entries.size(); i++) {
            int x = panelX + GRID_X + (i % GRID_COLUMNS) * 25;
            int y = panelY + GRID_Y + (i / GRID_COLUMNS) * 25;
            buttons.add(new GuiButtonRecipePhantom(entries.get(i), displayContext, x, y));
        }
    }

    private record CategoryTab(Object category, ItemStack icon, int x, int y) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + CATEGORY_SIZE && mouseY >= y && mouseY < y + CATEGORY_SIZE;
        }
    }
}
