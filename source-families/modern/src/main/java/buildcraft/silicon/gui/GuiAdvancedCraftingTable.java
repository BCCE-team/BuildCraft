//? source if >=1.21.1
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.silicon.gui;


import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;

import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.ledger.LedgerHelp;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.recipe.GuiRecipeBookPhantom;
import buildcraft.lib.gui.slot.SlotDisplay;
import buildcraft.silicon.container.ContainerAdvancedCraftingTable;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import buildcraft.lib.gui.help.GuiHelpUtil;
import buildcraft.lib.compat.RenderCompat;

public class GuiAdvancedCraftingTable extends GuiBC8<ContainerAdvancedCraftingTable> {
    private static final Identifier TEXTURE_BASE = Identifier.parse("buildcraftsilicon:textures/gui/advanced_crafting_table.png");
    private static final int SIZE_X = 176, SIZE_Y = 241;
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);
    private static final GuiIcon ICON_PROGRESS = new GuiIcon(TEXTURE_BASE, SIZE_X, 0, 4, 70);
    private static final GuiRectangle RECT_PROGRESS = new GuiRectangle(164, 7, 4, 70);

    private final GuiRecipeBookPhantom recipeBook;
    /** If true then the recipe book will be drawn on top of this GUI, rather than beside it */
    private boolean widthTooNarrow;
    private Button recipeButton;

    public GuiAdvancedCraftingTable(ContainerAdvancedCraftingTable container, Inventory inv, Component title) {
        super(container, inv, title);
        imageWidth = SIZE_X;
        imageHeight = SIZE_Y;
        recipeBook = new GuiRecipeBookPhantom(this::sendRecipe);
        mainGui.shownElements.add(new LedgerHelp(mainGui, true));
        mainGui.shownElements.add(new LedgerTablePower(mainGui, container.tile, true));
        GuiHelpUtil.addSlots(mainGui, 33, 16, 3, 3, "buildcraft.help.advanced_crafting.recipe.title", 0xFF_66_AA_FF, "buildcraft.help.advanced_crafting.recipe.desc");
        GuiHelpUtil.addSlots(mainGui, 15, 85, 5, 3, "buildcraft.help.advanced_crafting.materials.title", 0xFF_88_CC_88, "buildcraft.help.advanced_crafting.materials.desc");
        GuiHelpUtil.addSlots(mainGui, 109, 85, 3, 3, "buildcraft.help.advanced_crafting.outputs.title", 0xFF_DD_CC_55, "buildcraft.help.advanced_crafting.outputs.desc");
        GuiHelpUtil.addSlot(mainGui, 127, 33, "buildcraft.help.advanced_crafting.preview.title", 0xFF_CC_AA_FF, "buildcraft.help.advanced_crafting.preview.desc");
        GuiHelpUtil.addRoot(mainGui, (int) RECT_PROGRESS.x - 2, (int) RECT_PROGRESS.y - 1,
            (int) RECT_PROGRESS.width + 4, (int) RECT_PROGRESS.height + 2,
            "buildcraft.help.advanced_crafting.power.title", 0xFF_D4_6C_1F, "buildcraft.help.advanced_crafting.power.desc");
    }

    private void sendRecipe(RecipeDisplay display) {
        GuiRecipeBookPhantom.resolveCraftingGrid(display, minecraft.level).ifPresent(stacks ->
            container.sendSetPhantomSlots(container.blueprintInv, stacks)
        );
    }

    protected boolean shouldAddHelpLedger() {
        // Don't add it on the left side because it clashes with the recipe book
        return false;
    }

    public void init() {
        super.init();
        widthTooNarrow = this.width < SIZE_X + 176;
        recipeBook.init(width, height, minecraft, widthTooNarrow, contents -> {
            for (int slot = 0; slot < container.materialInv.getSlots(); slot++) {
                ItemStack stack = container.materialInv.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    contents.accountStack(stack);
                }
            }
        });
        leftPos = recipeBook.updateScreenPosition(width, imageWidth);
        recipeButton = new ImageButton(leftPos + 5, height / 2 - 90, 20, 18,
            RecipeBookComponent.RECIPE_BUTTON_SPRITES, this::onPress,
            Component.translatable("gui.recipebook.toggleRecipes"));
        addRenderableWidget(recipeButton);
    }

    public void containerTick() {
        super.containerTick();
        recipeBook.tick();
    }

    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
        super.render(guiGraphics, mouseX, mouseY, partialTicks);
        recipeBook.render(guiGraphics, mouseX, mouseY, partialTicks);
        recipeBook.renderTooltip(guiGraphics, this.leftPos, this.topPos, mouseX, mouseY);
    }


    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        ICON_GUI.drawAt(getActiveGraphics(), mainGui.rootElement);

        long target = container.tile.getGuiTarget();
        if (target != 0) {
            double v = (double) container.tile.power / target;
            ICON_PROGRESS.drawCutInside(
                    getActiveGraphics(), new GuiRectangle(
                            RECT_PROGRESS.x,
                            (int) (RECT_PROGRESS.y + RECT_PROGRESS.height * Math.max(1 - v, 0)),
                            RECT_PROGRESS.width,
                            (int) Math.ceil(RECT_PROGRESS.height * Math.min(v, 1))
                    ).offset(mainGui.rootElement)
            );
        }
    }

    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
        //font.drawString(title, titleLabelX + (imageWidth - font.getStringWidth(title)) / 2, titleLabelY + 5, 0x404040);
    }

    protected void onPress(Button button){
        if (button == recipeButton) {
            recipeBook.toggleVisibility();
            leftPos = recipeBook.updateScreenPosition(width, imageWidth);
            recipeButton.setPosition(this.leftPos + 5, this.height / 2 - 90);
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

    public boolean keyPressed(int a, int b, int c) {
        return RenderCompat.keyPressed(recipeBook, a, b, c) || super.keyPressed(a, b, c);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return recipeBook.charTyped(codePoint, modifiers) || super.charTyped(codePoint, modifiers);
    }

    protected void slotClicked(Slot slot, int slotId, int mouseButton, ClickType type) {
        if (slot instanceof SlotDisplay && container.tile != null && container.tile.getRecipeSelectionCount() > 1
                && (mouseButton == 0 || mouseButton == 1)) {
            int button = mouseButton == 1
                ? ContainerAdvancedCraftingTable.BUTTON_PREVIOUS_RECIPE
                : ContainerAdvancedCraftingTable.BUTTON_NEXT_RECIPE;
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

    protected boolean hasClickedOutside(double mouseX, double mouseY, int _guiLeft, int _guiTop, int p_97761_) {
        boolean outsideMachine = mouseX < _guiLeft || mouseY < _guiTop
            || mouseX >= _guiLeft + imageWidth || mouseY >= _guiTop + imageHeight;
        return recipeBook.hasClickedOutside(mouseX, mouseY, leftPos, topPos, imageWidth, imageHeight)
            && outsideMachine;
    }

    public void onClose() {
        super.onClose();
    }

}
