/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.list;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.list.ListMatchType;
import buildcraft.core.BCCoreItems;
import buildcraft.core.list.ContainerList.WidgetListSlot;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.button.GuiImageButton;
import buildcraft.lib.gui.button.IButtonBehaviour;
import buildcraft.lib.gui.button.IButtonClickEventListener;
import buildcraft.lib.gui.button.IButtonClickEventTrigger;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.list.ListHandler;
import buildcraft.lib.misc.StackUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.gui.help.GuiHelpUtil;
import buildcraft.lib.gui.ledger.LedgerHelp;

public class GuiList extends GuiBC8<ContainerList> implements IButtonClickEventListener {
    private static final ResourceLocation TEXTURE_BASE =
        new ResourceLocation("buildcraftcore:textures/gui/list_new.png");
    private static final int SIZE_X = 176, SIZE_Y = 191;
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);
    private static final GuiIcon ICON_HIGHLIGHT = new GuiIcon(TEXTURE_BASE, 176, 0, 16, 16);
    private static final GuiIcon ICON_ONE_STACK = new GuiIcon(TEXTURE_BASE, 0, 191, 20, 20);
    private static final int BUTTON_COUNT = 3;

    private final Map<Integer, Map<ListMatchType, NonNullList<ItemStack>>> exampleCache = new HashMap<>();
    private EditBox textField;

    public GuiList(ContainerList container, Inventory inv, Component title) {
        super(container, inv, title);
        imageWidth = SIZE_X;
        imageHeight = SIZE_Y;
    }

    @Override
    public void init() {
        super.init();

        mainGui.shownElements.clear();
        for (int line = 0; line < container.slots.length; line++) {
            WidgetListSlot[] arr = container.slots[line];
            for (int slot = 0; slot < arr.length; slot++) {
                final WidgetListSlot listSlot = arr[slot];
                GuiRectangle rectangle = new GuiRectangle(8 + slot * 18, 32 + line * 34, 16, 16);

                IGuiArea phantomSlotArea = rectangle.offset(mainGui.rootElement);
                mainGui.shownElements.add(listSlot.new GuiElementPhantomSlot(mainGui, phantomSlotArea) {
                    @Override
                    protected boolean shouldDrawHighlight() {
                        if (listSlot.slotIndex == 0) {
                            return true;
                        }
                        return !GuiList.this.container.lines[listSlot.lineIndex].isOneStackMode();
                    }

                    @Override
                    public void drawBackground(GuiGraphics guiGraphics, float partialTicks) {
                        if (!shouldDrawHighlight()) {
                            ICON_HIGHLIGHT.drawAt(guiGraphics, this);
                        }
                    }

                    @Nonnull
                    @Override
                    public ItemStack getStack() {
                        if (shouldDrawHighlight()) {
                            return super.getStack();
                        } else {
                            NonNullList<ItemStack> data = GuiList.this.getExamplesList(listSlot.lineIndex,
                                container.lines[listSlot.lineIndex].getSortingType());
                            if (data.size() >= listSlot.slotIndex) {
                                return data.get(listSlot.slotIndex - 1);
                            } else {
                                return StackUtil.EMPTY;
                            }
                        }
                    }

                    @Override
                    public void onMouseClicked(int button) {
                        super.onMouseClicked(button);
                        if (contains(gui.mouse)) {
                            clearExamplesCache(listSlot.lineIndex);
                        }
                    }
                });
            }
        }

        //buttonList.clear();

        for (int sy = 0; sy < ListHandler.HEIGHT; sy++) {
            int bOff = sy * BUTTON_COUNT;
            int bOffX = this.leftPos + 8 + ListHandler.WIDTH * 18 - BUTTON_COUNT * 11;
            int bOffY = this.topPos + 32 + sy * 34 + 18;

            GuiImageButton buttonPrecise =
                new GuiImageButton(mainGui, bOff + 0, bOffX, bOffY, 11, TEXTURE_BASE, 176, 16, 176, 28);
            buttonPrecise.setToolTip(ToolTip.createLocalized("gui.list.nbt"));
            buttonPrecise.setBehaviour(IButtonBehaviour.TOGGLE);
            mainGui.shownElements.add(buttonPrecise);

            GuiImageButton buttonType =
                new GuiImageButton(mainGui, bOff + 1, bOffX + 11, bOffY, 11, TEXTURE_BASE, 176, 16, 185, 28);
            buttonType.setToolTip(ToolTip.createLocalized("gui.list.metadata"));
            buttonType.setBehaviour(IButtonBehaviour.TOGGLE);
            mainGui.shownElements.add(buttonType);

            GuiImageButton buttonMaterial =
                new GuiImageButton(mainGui, bOff + 2, bOffX + 22, bOffY, 11, TEXTURE_BASE, 176, 16, 194, 28);
            buttonMaterial.setToolTip(ToolTip.createLocalized("gui.list.oredict"));
            buttonMaterial.setBehaviour(IButtonBehaviour.TOGGLE);
            mainGui.shownElements.add(buttonMaterial);
        }

        for (IGuiElement elem : mainGui.shownElements) {
            if (elem instanceof GuiImageButton) {
                GuiImageButton b = (GuiImageButton) elem;
                int id = Integer.parseInt(b.id);
                int lineId = id / BUTTON_COUNT;
                int buttonId = id % BUTTON_COUNT;
                if (container.lines[lineId].getOption(buttonId)) {
                    b.activate();
                }

                b.registerListener(this);
            }
        }

        addHelpElements();
        mainGui.shownElements.add(new LedgerHelp(mainGui, false));

        textField = new EditBox(font, leftPos + 10, topPos + 10, 156, 12, Component.empty());
        textField.setValue(BuildCraftApi.service(BuildCraftServices.ITEM_LABELS).label(container.getListItemStack()).orElse(""));
        textField.setMaxLength(32);
        textField.setResponder(container::setLabel);
        addRenderableWidget(textField);
        setInitialFocus(textField);
    }


    private void addHelpElements() {
        GuiHelpUtil.addRoot(mainGui, 8, 8, 160, 18, "buildcraft.help.list.name.title", 0xFF_66_AA_FF,
            "buildcraft.help.list.name.desc");

        for (int line = 0; line < ListHandler.HEIGHT; line++) {
            int slotY = 32 + line * 34;
            int buttonX = 8 + ListHandler.WIDTH * 18 - BUTTON_COUNT * 11;
            int buttonY = slotY + 18;

            GuiHelpUtil.addSlot(mainGui, 8, slotY, "buildcraft.help.list.reference.title", 0xFF_88_CC_88,
                "buildcraft.help.list.reference.desc");
            GuiHelpUtil.addRoot(mainGui, 25, slotY - 1, (ListHandler.WIDTH - 1) * 18, 18,
                "buildcraft.help.list.extra_entries.title", 0xFF_77_BB_99,
                "buildcraft.help.list.extra_entries.desc");

            GuiHelpUtil.addRoot(mainGui, buttonX, buttonY, 11, 11, "buildcraft.help.list.precise.title",
                0xFF_DD_CC_55, "buildcraft.help.list.precise.desc");
            GuiHelpUtil.addRoot(mainGui, buttonX + 11, buttonY, 11, 11, "buildcraft.help.list.type.title",
                0xFF_CC_AA_FF, "buildcraft.help.list.type.desc");
            GuiHelpUtil.addRoot(mainGui, buttonX + 22, buttonY, 11, 11, "buildcraft.help.list.material.title",
                0xFF_55_BB_DD, "buildcraft.help.list.material.desc");
        }
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        GuiGraphics guiGraphics = getActiveGraphics();
        ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);

        for (int i = 0; i < 2; i++) {
            if (container.lines[i].isOneStackMode()) {
                ICON_ONE_STACK.drawAt(guiGraphics, leftPos + 6, topPos + 30 + i * 34);
            }
        }
    }

    @Override
    public boolean keyPressed(int a, int b, int c){
		if (a == 256) {
			this.minecraft.player.closeContainer();
		}
		return !this.textField.keyPressed(a, b, c) && !this.textField.canConsumeInput() ? super.keyPressed(a, b, c) : true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int b){
        return super.mouseClicked(x, y, b);
        
    }

    @Override
    public void handleButtonClick(IButtonClickEventTrigger sender, int buttonKey) {
        if (!(sender instanceof GuiImageButton)) {
            return;
        }
        int id = Integer.parseInt(((GuiImageButton) sender).id);
        int buttonId = id % BUTTON_COUNT;
        int lineId = id / BUTTON_COUNT;

        container.switchButton(lineId, buttonId);
        clearExamplesCache(lineId);
    }

    private void clearExamplesCache(int lineId) {
        Map<ListMatchType, NonNullList<ItemStack>> exampleList = exampleCache.get(lineId);
        if (exampleList != null) {
            exampleList.clear();
        }
    }

    private NonNullList<ItemStack> getExamplesList(int lineId, ListMatchType type) {
        Map<ListMatchType, NonNullList<ItemStack>> exampleList =
            exampleCache.computeIfAbsent(lineId, k -> new EnumMap<>(ListMatchType.class));

        if (!exampleList.containsKey(type)) {
            NonNullList<ItemStack> examples = container.lines[lineId].getExamples();
            ItemStack input = container.lines[lineId].stacks.get(0);
            if (!input.isEmpty()) {
                NonNullList<ItemStack> repetitions = NonNullList.create();
                for (ItemStack is : examples) {
                    if (StackUtil.isMatchingItem(input, is, true, false)) {
                        repetitions.add(is);
                    }
                }
                examples.removeAll(repetitions);
            }
            exampleList.put(type, examples);
        }
        return exampleList.get(type);
    }
}
