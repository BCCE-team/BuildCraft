//? source if >=1.21.11
package buildcraft.lib.gui.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRecipeBookChangeSettingsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeBookCategories;
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
    private static final int PANEL_WIDTH = 147;
    private static final int PANEL_HEIGHT = 166;
    private static final int GRID_COLUMNS = 5;
    private static final int CATEGORY_WIDTH = 35;
    private static final int CATEGORY_HEIGHT = 27;
    private static final Identifier RECIPE_BOOK_LOCATION = Identifier.withDefaultNamespace("textures/gui/recipe_book.png");
    private static final Identifier TAB = Identifier.withDefaultNamespace("recipe_book/tab");
    private static final Identifier TAB_SELECTED = Identifier.withDefaultNamespace("recipe_book/tab_selected");
    private static final Identifier PAGE_FORWARD = Identifier.withDefaultNamespace("recipe_book/page_forward");
    private static final Identifier PAGE_FORWARD_HIGHLIGHTED = Identifier.withDefaultNamespace("recipe_book/page_forward_highlighted");
    private static final Identifier PAGE_BACKWARD = Identifier.withDefaultNamespace("recipe_book/page_backward");
    private static final Identifier PAGE_BACKWARD_HIGHLIGHTED = Identifier.withDefaultNamespace("recipe_book/page_backward_highlighted");
    private static final WidgetSprites FILTER_BUTTON_SPRITES = new WidgetSprites(
        Identifier.withDefaultNamespace("recipe_book/filter_enabled"),
        Identifier.withDefaultNamespace("recipe_book/filter_disabled"),
        Identifier.withDefaultNamespace("recipe_book/filter_enabled_highlighted"),
        Identifier.withDefaultNamespace("recipe_book/filter_disabled_highlighted")
    );
    private static final Component ONLY_CRAFTABLES_TOOLTIP =
        Component.translatable("gui.recipebook.toggleRecipes.craftable");
    private static final Component ALL_RECIPES_TOOLTIP =
        Component.translatable("gui.recipebook.toggleRecipes.all");

    public final Consumer<RecipeDisplay> recipeSetter;

    private final RecipeBookPagePhantom page = new RecipeBookPagePhantom();
    private final List<GuiButtonRecipePhantom> buttons = new ArrayList<>();
    private final List<CategoryTab> categoryTabs = new ArrayList<>();
    private final StackedItemContents stackedContents = new StackedItemContents();

    private Minecraft minecraft;
    private ContextMap displayContext;
    private EditBox searchBox;
    private CycleButton<Boolean> filterButton;
    private Consumer<StackedItemContents> materialContentsFiller = ignored -> {};
    private List<RecipeDisplayEntry> allEntries = List.of();
    private Object selectedCategory;
    private boolean visible;
    private boolean narrow;
    private int screenWidth;
    private int screenHeight;
    private int panelX;
    private int panelY;
    private int refreshTicker;
    private int inventoryChangeCount = -1;

    public GuiRecipeBookPhantom(Consumer<RecipeDisplay> recipeSetter) {
        this.recipeSetter = recipeSetter;
    }

    public void init(int width, int height, Minecraft minecraft, boolean narrow,
        Consumer<StackedItemContents> materialContentsFiller) {
        String previousSearch = searchBox == null ? "" : searchBox.getValue();
        this.minecraft = minecraft;
        this.narrow = narrow;
        this.screenWidth = width;
        this.screenHeight = height;
        this.materialContentsFiller = materialContentsFiller == null ? ignored -> {} : materialContentsFiller;
        this.displayContext = minecraft.level == null ? null : SlotDisplayContext.fromLevel(minecraft.level);
        this.inventoryChangeCount = minecraft.player == null ? -1 : minecraft.player.getInventory().getTimesChanged();
        refreshStackedContents();
        updatePanelPosition((width - 176) / 2);

        searchBox = new EditBox(minecraft.font, panelX + 25, panelY + 13, 81, 14,
            Component.translatable("gui.recipebook.search_hint"));
        searchBox.setMaxLength(50);
        searchBox.setValue(previousSearch);
        searchBox.setResponder(value -> applyFilters(true));

        filterButton = CycleButton.booleanBuilder(ONLY_CRAFTABLES_TOOLTIP, ALL_RECIPES_TOOLTIP, isFiltering())
            .withTooltip(value -> value ? Tooltip.create(ONLY_CRAFTABLES_TOOLTIP) : Tooltip.create(ALL_RECIPES_TOOLTIP))
            .withSprite((button, value) -> FILTER_BUTTON_SPRITES.get(value, button.isHoveredOrFocused()))
            .displayState(CycleButton.DisplayState.HIDE)
            .create(panelX + 110, panelY + 12, 26, 16, CommonComponents.EMPTY, (button, value) -> {
                setFiltering(value);
                applyFilters(true);
            });

        rebuildRecipes(false);
    }

    public int updateScreenPosition(int width, int imageWidth) {
        this.screenWidth = width;
        int left = visible && !narrow
            ? 177 + (width - imageWidth - 200) / 2
            : (width - imageWidth) / 2;
        updatePanelPosition(left);
        return left;
    }

    private void updatePanelPosition(int guiLeft) {
        panelX = narrow ? (screenWidth - PANEL_WIDTH) / 2 : (screenWidth - PANEL_WIDTH) / 2 - 86;
        panelY = (screenHeight - PANEL_HEIGHT) / 2;
        if (searchBox != null) {
            searchBox.setPosition(panelX + 25, panelY + 13);
        }
        if (filterButton != null) {
            filterButton.setPosition(panelX + 110, panelY + 12);
        }
        rebuildButtons();
        rebuildCategoryTabs();
    }

    public void tick() {
        if (minecraft != null && minecraft.player != null) {
            int changed = minecraft.player.getInventory().getTimesChanged();
            if (changed != inventoryChangeCount) {
                inventoryChangeCount = changed;
                refreshStackedContents();
                applyFilters(false);
            }
            if (filterButton != null && filterButton.getValue() != isFiltering()) {
                filterButton.setValue(isFiltering());
                applyFilters(false);
            }
        }
        if (++refreshTicker >= 20) {
            refreshTicker = 0;
            refreshStackedContents();
            rebuildRecipes(false);
        }
    }

    public boolean isVisible() {
        return visible;
    }

    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (!visible || minecraft == null) return;

        graphics.nextStratum();
        // Use the actual vanilla Recipe Book texture and sprite set. This is intentionally not a BCCE
        // recreation: geometry, spacing and controls mirror RecipeBookComponent/RecipeBookPage 1.21.11.
        graphics.blit(RenderPipelines.GUI_TEXTURED, RECIPE_BOOK_LOCATION, panelX, panelY,
            1.0F, 1.0F, PANEL_WIDTH, PANEL_HEIGHT, 256, 256);
        if (searchBox != null) {
            searchBox.render(graphics, mouseX, mouseY, partialTicks);
        }
        if (filterButton != null) {
            filterButton.render(graphics, mouseX, mouseY, partialTicks);
        }

        for (CategoryTab tab : categoryTabs) {
            boolean selected = Objects.equals(selectedCategory, tab.category());
            int drawX = selected ? tab.x() - 2 : tab.x();
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, selected ? TAB_SELECTED : TAB,
                drawX, tab.y(), CATEGORY_WIDTH, CATEGORY_HEIGHT);
            int iconOffset = selected ? -2 : 0;
            if (!tab.secondaryIcon().isEmpty()) {
                graphics.renderFakeItem(tab.primaryIcon(), tab.x() + 3 + iconOffset, tab.y() + 5);
                graphics.renderFakeItem(tab.secondaryIcon(), tab.x() + 14 + iconOffset, tab.y() + 5);
            } else if (!tab.primaryIcon().isEmpty()) {
                graphics.renderFakeItem(tab.primaryIcon(), tab.x() + 9 + iconOffset, tab.y() + 5);
            }
        }

        for (GuiButtonRecipePhantom button : buttons) {
            button.render(graphics, button.contains(mouseX, mouseY));
        }

        if (page.pageCount() > 1) {
            Component pageText = Component.translatable("gui.recipebook.page", page.page() + 1, page.pageCount());
            int textWidth = minecraft.font.width(pageText);
            graphics.drawString(minecraft.font, pageText, panelX + 73 - textWidth / 2, panelY + 141, -1);

            if (page.page() > 0) {
                boolean hovered = mouseX >= panelX + 38 && mouseX < panelX + 50
                    && mouseY >= panelY + 137 && mouseY < panelY + 154;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                    hovered ? PAGE_BACKWARD_HIGHLIGHTED : PAGE_BACKWARD, panelX + 38, panelY + 137, 12, 17);
            }
            if (page.page() + 1 < page.pageCount()) {
                boolean hovered = mouseX >= panelX + 93 && mouseX < panelX + 105
                    && mouseY >= panelY + 137 && mouseY < panelY + 154;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED,
                    hovered ? PAGE_FORWARD_HIGHLIGHTED : PAGE_FORWARD, panelX + 93, panelY + 137, 12, 17);
            }
        }
    }

    public void renderGhostRecipe(GuiGraphics graphics, int leftPos, int topPos, boolean big, float partialTicks) {
        // Selecting a recipe fills the BuildCraft phantom slots directly.
    }

    public void renderTooltip(GuiGraphics graphics, int leftPos, int topPos, int mouseX, int mouseY) {
        if (!visible || minecraft == null) return;
        for (CategoryTab tab : categoryTabs) {
            if (tab.contains(mouseX, mouseY) && !tab.primaryIcon().isEmpty()) {
                graphics.setTooltipForNextFrame(minecraft.font, tab.primaryIcon(), mouseX, mouseY);
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
            if (filterButton != null && BCGuiInput.click(filterButton, mouseX, mouseY, mouseButton)) {
                return true;
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
            if (mouseY >= panelY + 137 && mouseY < panelY + 154) {
                if (mouseX >= panelX + 38 && mouseX < panelX + 50 && page.page() > 0) {
                    if (page.previous()) rebuildButtons();
                    return true;
                }
                if (mouseX >= panelX + 93 && mouseX < panelX + 105 && page.page() + 1 < page.pageCount()) {
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
            // Shapeless recipes fill the phantom crafting grid row-major from the top-left.
            recipeWidth = 3;
            recipeHeight = 3;
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
            if (isFiltering() && !entry.canCraft(stackedContents)) {
                continue;
            }
            filtered.add(entry);
        }
        if (resetPage) page.reset();
        page.setEntries(filtered);
        rebuildButtons();
    }

    private void refreshStackedContents() {
        stackedContents.clear();
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        minecraft.player.getInventory().fillStackedContents(stackedContents);
        materialContentsFiller.accept(stackedContents);
    }

    private boolean isFiltering() {
        return minecraft != null && minecraft.player != null
            && minecraft.player.getRecipeBook().isFiltering(RecipeBookType.CRAFTING);
    }

    private void setFiltering(boolean value) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        ClientRecipeBook recipeBook = minecraft.player.getRecipeBook();
        recipeBook.setFiltering(RecipeBookType.CRAFTING, value);
        if (minecraft.getConnection() != null) {
            minecraft.getConnection().send(new ServerboundRecipeBookChangeSettingsPacket(
                RecipeBookType.CRAFTING, recipeBook.isOpen(RecipeBookType.CRAFTING), value
            ));
        }
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

        int x = panelX - 30;
        int y = panelY + 3;
        int index = 0;
        // Match vanilla CraftingRecipeBookComponent.TABS exactly. Search is always visible; the other
        // four tabs appear only when their vanilla recipe-book category has at least one entry.
        categoryTabs.add(new CategoryTab(null, new ItemStack(Items.COMPASS), ItemStack.EMPTY, x, y + 27 * index++));
        index = addVanillaCategoryTab(index, RecipeBookCategories.CRAFTING_EQUIPMENT,
            new ItemStack(Items.IRON_AXE), new ItemStack(Items.GOLDEN_SWORD), x, y);
        index = addVanillaCategoryTab(index, RecipeBookCategories.CRAFTING_BUILDING_BLOCKS,
            new ItemStack(Items.BRICKS), ItemStack.EMPTY, x, y);
        index = addVanillaCategoryTab(index, RecipeBookCategories.CRAFTING_MISC,
            new ItemStack(Items.LAVA_BUCKET), new ItemStack(Items.APPLE), x, y);
        addVanillaCategoryTab(index, RecipeBookCategories.CRAFTING_REDSTONE,
            new ItemStack(Items.REDSTONE), ItemStack.EMPTY, x, y);
    }

    private int addVanillaCategoryTab(int index, Object category, ItemStack primary, ItemStack secondary, int x, int y) {
        if (allEntries.stream().noneMatch(entry -> Objects.equals(entry.category(), category))) {
            return index;
        }
        categoryTabs.add(new CategoryTab(category, primary, secondary, x, y + 27 * index));
        return index + 1;
    }

    private void rebuildButtons() {
        buttons.clear();
        if (displayContext == null) return;
        List<RecipeDisplayEntry> entries = page.visibleEntries();
        for (int i = 0; i < entries.size(); i++) {
            int x = panelX + 11 + (i % GRID_COLUMNS) * 25;
            int y = panelY + 31 + (i / GRID_COLUMNS) * 25;
            RecipeDisplayEntry entry = entries.get(i);
            buttons.add(new GuiButtonRecipePhantom(entry, displayContext, entry.canCraft(stackedContents), x, y));
        }
    }

    private record CategoryTab(Object category, ItemStack primaryIcon, ItemStack secondaryIcon, int x, int y) {
        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + CATEGORY_WIDTH && mouseY >= y && mouseY < y + CATEGORY_HEIGHT;
        }
    }
}
