//? source if >=1.21.1
package buildcraft.factory.gui;

import buildcraft.factory.container.ContainerAutoCraftItems;
import buildcraft.lib.compat.RenderCompat;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.help.GuiHelpUtil;
import buildcraft.lib.gui.ledger.LedgerHelp;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.recipe.GuiRecipeBookPhantom;
import buildcraft.lib.gui.slot.SlotBase;
import buildcraft.lib.gui.slot.SlotDisplay;
import buildcraft.lib.misc.StackUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplay;

/** 1.21.11 autobench screen with a native RecipeDisplay-backed phantom recipe panel. */
public class GuiAutoCraftItems extends GuiBC8<ContainerAutoCraftItems> {
    private static final Identifier TEXTURE_BASE = Identifier.parse("buildcraftfactory:textures/gui/autobench_item.png");
    private static final Identifier TEXTURE_MISC = Identifier.parse("buildcraftlib:textures/gui/misc_slots.png");
    private static final int SIZE_X = 176;
    private static final int SIZE_Y = 197;
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);
    private static final GuiIcon ICON_FILTER_OVERLAY_SAME = new GuiIcon(TEXTURE_MISC, 54, 0, 18, 18);
    private static final GuiIcon ICON_FILTER_OVERLAY_DIFFERENT = new GuiIcon(TEXTURE_MISC, 72, 0, 18, 18);
    private static final GuiIcon ICON_PROGRESS = new GuiIcon(TEXTURE_BASE, SIZE_X, 0, 23, 10);
    private static final GuiRectangle RECT_PROGRESS = new GuiRectangle(90, 47, 23, 10);

    private final GuiRecipeBookPhantom recipeBook;
    private boolean widthTooNarrow;
    private Button recipeButton;

    public GuiAutoCraftItems(ContainerAutoCraftItems container, Inventory inv, Component title) {
        super(container, inv, title);
        imageWidth = SIZE_X;
        imageHeight = SIZE_Y;
        recipeBook = new GuiRecipeBookPhantom(this::sendRecipe);
        mainGui.shownElements.add(new LedgerHelp(mainGui, true));
        GuiHelpUtil.addSlots(mainGui, 30, 17, 3, 3, "buildcraft.help.autoworkbench.recipe.title", 0xFF_66_AA_FF, "buildcraft.help.autoworkbench.recipe.desc");
        GuiHelpUtil.addSlots(mainGui, 8, 84, 9, 1, "buildcraft.help.autoworkbench.materials.title", 0xFF_88_CC_88, "buildcraft.help.autoworkbench.materials.desc");
        GuiHelpUtil.addSlot(mainGui, 124, 35, "buildcraft.help.autoworkbench.result.title", 0xFF_DD_CC_55, "buildcraft.help.autoworkbench.result.desc");
        GuiHelpUtil.addSlot(mainGui, 93, 27, "buildcraft.help.autoworkbench.preview.title", 0xFF_CC_AA_FF, "buildcraft.help.autoworkbench.preview.desc");
        GuiHelpUtil.addRoot(mainGui, 90, 47, 23, 10, "buildcraft.help.autoworkbench.progress.title", 0xFF_CC_AA_FF, "buildcraft.help.autoworkbench.progress.desc");
    }

    private void sendRecipe(RecipeDisplay display) {
        container.sendSetPhantomSlots(
            container.blueprintInv,
            GuiRecipeBookPhantom.resolveCraftingGrid(display, minecraft.level)
        );
    }

    protected boolean shouldAddHelpLedger() {
        return false;
    }

    public void init() {
        super.init();
        widthTooNarrow = this.width < SIZE_X + 176;
        recipeBook.init(width, height, minecraft, widthTooNarrow);
        leftPos = recipeBook.updateScreenPosition(width, imageWidth);
        recipeButton = Button.builder(Component.literal("R"), this::onPress)
            .pos(leftPos + 5, height / 2 - 66)
            .size(20, 18)
            .build();
        addRenderableWidget(recipeButton);
    }

    public void containerTick() {
        super.containerTick();
        recipeBook.tick();
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        recipeBook.render(guiGraphics, mouseX, mouseY, partialTicks);
        recipeBook.renderTooltip(guiGraphics, leftPos, topPos, mouseX, mouseY);
    }

    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics guiGraphics = getActiveGraphics();
        ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);

        double progress = container.tile.getProgress(partialTicks);
        drawProgress(guiGraphics, RECT_PROGRESS, ICON_PROGRESS, progress, 1);

        if (!hasFilters()) {
            return;
        }

        forEachFilter((slot, filterStack) -> {
            int x = slot.x + (int) mainGui.rootElement.getX();
            int y = slot.y + (int) mainGui.rootElement.getY();
            guiGraphics.renderItem(filterStack, x, y);
            guiGraphics.renderItemDecorations(font, filterStack, x, y);
        });

        RenderCompat.disableDepthTest();
        forEachFilter((slot, filterStack) -> {
            ItemStack real = slot.getItem();
            GuiIcon icon = real.isEmpty() || StackUtil.canMerge(real, filterStack)
                ? ICON_FILTER_OVERLAY_SAME
                : ICON_FILTER_OVERLAY_DIFFERENT;
            int x = slot.x + (int) mainGui.rootElement.getX();
            int y = slot.y + (int) mainGui.rootElement.getY();
            icon.drawAt(guiGraphics, x - 1, y - 1);
        });
        RenderCompat.enableDepthTest();
    }

    private boolean hasFilters() {
        for (SlotBase filterSlot : container.filtterSlots) {
            if (!filterSlot.getItem().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void forEachFilter(IFilterSlotIterator iter) {
        SlotBase[] filters = container.filtterSlots;
        for (int s = 0; s < filters.length; s++) {
            ItemStack filter = filters[s].getItem();
            if (!filter.isEmpty()) {
                iter.iterate(container.materialSlots[s], filter);
            }
        }
    }

    protected void onPress(Button button) {
        if (button == recipeButton) {
            recipeBook.toggleVisibility();
            leftPos = recipeBook.updateScreenPosition(width, imageWidth);
            recipeButton.setPosition(leftPos + 5, height / 2 - 66);
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (RenderCompat.mouseClicked(recipeBook, mouseX, mouseY, mouseButton)) {
            return true;
        }
        if (widthTooNarrow && recipeBook.isVisible()) {
            return false;
        }
        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return RenderCompat.keyPressed(recipeBook, keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return recipeBook.charTyped(codePoint, modifiers) || super.charTyped(codePoint, modifiers);
    }

    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot instanceof SlotDisplay && container.tile != null && container.tile.getRecipeSelectionCount() > 1
            && (mouseButton == 0 || mouseButton == 1)) {
            int button = mouseButton == 1
                ? ContainerAutoCraftItems.BUTTON_PREVIOUS_RECIPE
                : ContainerAutoCraftItems.BUTTON_NEXT_RECIPE;
            minecraft.gameMode.handleInventoryButtonClick(container.containerId, button);
            return;
        }
        super.slotClicked(slot, slotId, mouseButton, type);
        recipeBook.slotClicked(slot);
    }

    protected boolean isHovering(int rectX, int rectY, int rectWidth, int rectHeight, double pointX, double pointY) {
        return (!widthTooNarrow || !recipeBook.isVisible())
            && super.isHovering(rectX, rectY, rectWidth, rectHeight, pointX, pointY);
    }

    protected boolean hasClickedOutside(double mouseX, double mouseY, int guiLeft, int guiTop) {
        boolean outsideMachine = mouseX < guiLeft || mouseY < guiTop
            || mouseX >= guiLeft + imageWidth || mouseY >= guiTop + imageHeight;
        return recipeBook.hasClickedOutside(mouseX, mouseY, leftPos, topPos, imageWidth, imageHeight)
            && outsideMachine;
    }

    @FunctionalInterface
    private interface IFilterSlotIterator {
        void iterate(SlotBase slot, ItemStack filterStack);
    }
}
