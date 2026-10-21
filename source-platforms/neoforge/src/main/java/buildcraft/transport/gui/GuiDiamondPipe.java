/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.lib.BCLibConfig;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.transport.container.ContainerDiamondPipe;
//? if mc_26_x && (>=26.3) {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//? } else {
import net.minecraft.client.gui.GuiGraphics;
//?}
import net.minecraft.network.chat.Component;
//? if mc_26_x && (>=26.3) {
import net.minecraft.resources.Identifier;
//? } else {
import net.minecraft.resources.ResourceLocation;
//?}
import net.minecraft.world.entity.player.Inventory;
//? if mc_26_x && (>=26.3) {
import buildcraft.lib.platform.storage.ItemStorage;
//? } else {
import net.neoforged.neoforge.items.IItemHandler;
//?}
import buildcraft.lib.gui.help.GuiHelpUtil;

public class GuiDiamondPipe extends GuiBC8<ContainerDiamondPipe> {
//? if mc_26_x && (>=26.3) {
    private static final Identifier TEXTURE = Identifier.parse("buildcrafttransport:textures/gui/filter.png");
    private static final Identifier TEXTURE_CB = Identifier.parse("buildcrafttransport:textures/gui/filter_cb.png");
//? } else {
    private static final ResourceLocation TEXTURE = ResourceLocation.parse("buildcrafttransport:textures/gui/filter.png");
    private static final ResourceLocation TEXTURE_CB = ResourceLocation.parse("buildcrafttransport:textures/gui/filter_cb.png");
//?}
    private static final int SIZE_X = 175, SIZE_Y = 225;
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE, 0, 0, SIZE_X, SIZE_Y);
    private static final GuiIcon ICON_GUI_CB = new GuiIcon(TEXTURE_CB, 0, 0, SIZE_X, SIZE_Y);

    Inventory playerInventory;
//? if mc_26_x && (>=26.3) {
    ItemStorage filterInventory;
//? } else {
    IItemHandler filterInventory;
//?}

    public GuiDiamondPipe(ContainerDiamondPipe container, Inventory inv, Component title) {
//? if mc_26_x && (>=26.3) {
        super(container, inv, title, SIZE_X, SIZE_Y);
//? } else {
        super(container, inv, title);
//?}
        this.playerInventory = inv;
        this.filterInventory = container.filters;
//? if mc_26_x && (>=26.3) {
//? } else {
        imageWidth = SIZE_X;
        imageHeight = SIZE_Y;
//?}
        GuiHelpUtil.addSlots(mainGui, 8, 18, 9, 6, "buildcraft.help.pipe.diamond.filters.title", 0xFF_66_AA_FF, "buildcraft.help.pipe.diamond.filters.desc");
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
//? if mc_26_x && (>=26.3) {
        GuiGraphicsExtractor guiGraphics = getActiveGraphics();
//? } else {
        GuiGraphics guiGraphics = getActiveGraphics();
//?}
        Component string = Component.translatable("gui.pipes.emerald.title");
        double titleX = mainGui.rootElement.getX() + 8;
        double titleY = mainGui.rootElement.getY() + 6;
//? if mc_26_x && (>=26.3) {
        guiGraphics.text(font, string, (int) titleX, (int) titleY, 0xFF404040, false);
//? } else {
        guiGraphics.drawString(font, string, (int) titleX, (int) titleY, 0x404040, false);
//?}

//? if mc_26_x && (>=26.3) {
        double invY = mainGui.rootElement.getY() + SIZE_Y - 97;
        guiGraphics.text(font, Component.translatable("gui.inventory"), (int) titleX, (int) invY, 0xFF404040, false);
//? } else {
        double invY = mainGui.rootElement.getY() + imageHeight - 97;
        guiGraphics.drawString(font, Component.translatable("gui.inventory"), (int) titleX, (int) invY, 0x404040, false);
//?}
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
//? if mc_26_x && (>=26.3) {
        GuiGraphicsExtractor guiGraphics = getActiveGraphics();
//? } else {
        GuiGraphics guiGraphics = getActiveGraphics();
//?}
        if (BCLibConfig.colourBlindMode) {
            ICON_GUI_CB.drawAt(guiGraphics, mainGui.rootElement);
        } else {
            ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);
        }
    }
}
