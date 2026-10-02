/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.transport.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.transport.container.ContainerFilteredBuffer_BC8;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import buildcraft.lib.gui.help.GuiHelpUtil;

public class GuiFilteredBuffer extends GuiBC8<ContainerFilteredBuffer_BC8> {
    private static final ResourceLocation TEXTURE_BASE = ResourceLocation.parse("buildcrafttransport:textures/gui/filtered_buffer.png");
    private static final int SIZE_X = 176, SIZE_Y = 169;
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);

    public GuiFilteredBuffer(ContainerFilteredBuffer_BC8 container, Inventory inv, Component title) {
        super(container, inv, title, SIZE_X, SIZE_Y);
        GuiHelpUtil.addSlots(mainGui, 8, 27, 9, 1, "buildcraft.help.filtered_buffer.filters.title", 0xFF_66_AA_FF, "buildcraft.help.filtered_buffer.filters.desc");
        GuiHelpUtil.addSlots(mainGui, 8, 61, 9, 1, "buildcraft.help.filtered_buffer.inventory.title", 0xFF_88_CC_88, "buildcraft.help.filtered_buffer.inventory.desc");
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        GuiGraphicsExtractor guiGraphics = getActiveGraphics();
        ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);
        guiGraphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_BASE,
            (int) mainGui.rootElement.getX(), (int) mainGui.rootElement.getY(),
            0.0F, 0.0F, SIZE_X, SIZE_Y, SIZE_X, SIZE_Y, 256, 256, 0xB3FFFFFF);
    }

    @Override
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
        GuiGraphicsExtractor guiGraphics = getActiveGraphics();
        Component title = Component.translatable("block.buildcrafttransport.filtered_buffer");
        int xPos = (SIZE_X - font.width(title)) / 2;
        guiGraphics.text(font, title, (int) mainGui.rootElement.getX() + xPos,
            (int) mainGui.rootElement.getY() + 10, 0xFF404040, false);
    }
}
