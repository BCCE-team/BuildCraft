/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.energy.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.energy.menu.ContainerEngineStone_BC8;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.help.DummyHelpElement;
import buildcraft.lib.gui.help.ElementHelpInfo;
import buildcraft.lib.gui.ledger.LedgerEngine;
import buildcraft.lib.gui.pos.GuiRectangle;
import buildcraft.lib.gui.pos.IGuiArea;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public class GuiEngineStone_BC8 extends GuiBC8<ContainerEngineStone_BC8> {
    private static final ResourceLocation TEXTURE_BASE = new ResourceLocation("buildcraftenergy:textures/gui/steam_engine_gui.png");
    private static final int SIZE_X = 176, SIZE_Y = 166;
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);

    private final IGuiArea flameRect = new GuiRectangle(81, 25, 14, 14).offset(mainGui.rootElement);
    private final IGuiArea fuelSlotRect = new GuiRectangle(78, 39, 20, 20).offset(mainGui.rootElement);
    private final ElementHelpInfo helpFlame, helpFuel;

    public GuiEngineStone_BC8(ContainerEngineStone_BC8 container, Inventory inv, Component title) {
        super(container, inv, title == Component.empty() ? 
        		Component.translatable("tile.engineStone.name").withStyle(Style.EMPTY.withColor(0x404040))
        		: title);
        imageWidth = SIZE_X;
        imageHeight = SIZE_Y;
        inventoryLabelX = 8;
        inventoryLabelY = SIZE_Y - 96;
        mainGui.shownElements.add(new LedgerEngine(mainGui, container.tile, true));
        helpFlame = new ElementHelpInfo("buildcraft.help.stone_engine.flame.title", 0xFF_FF_FF_1F, "buildcraft.help.stone_engine.flame");
        // Fuel-specific values remain runtime-driven; this help entry stays registry-agnostic.
        helpFuel = new ElementHelpInfo("buildcraft.help.stone_engine.fuel.title", 0xFF_AA_33_33, "buildcraft.help.stone_engine.fuel");
    }

    @Override
    public void init() {
        super.init();
        mainGui.shownElements.add(new DummyHelpElement(flameRect.expand(2), helpFlame));
        mainGui.shownElements.add(new DummyHelpElement(fuelSlotRect, helpFuel));
    }

    @Override
	protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        ICON_GUI.drawAt(pose, mainGui.rootElement);

        double amount = container.tile.deltaFuelLeft.getDynamic(partialTicks) / 100;

        if (amount > 0) {
            int flameHeight = (int) Math.ceil(amount * flameRect.getHeight());

            drawTexturedModalRect(//
                    pose, (int) flameRect.getX(),//
                    (int) (flameRect.getY() + flameRect.getHeight() - flameHeight),//
                    176, 14 - flameHeight, 14, flameHeight + 2);
        }
    }
}