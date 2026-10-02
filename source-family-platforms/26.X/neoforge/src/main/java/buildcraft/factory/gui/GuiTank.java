/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.gui;

import com.mojang.blaze3d.vertex.PoseStack;

import java.util.List;

import buildcraft.lib.fluid.FluidDisplayHelper;
import buildcraft.lib.fluid.FluidSmoother.FluidStackInterp;
import buildcraft.lib.misc.GuiUtil;
import buildcraft.lib.misc.LocaleUtil;
import net.minecraft.ChatFormatting;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import buildcraft.factory.container.ContainerTank;
import buildcraft.lib.gui.GuiBC8;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.component.TankComponent;
import buildcraft.lib.gui.help.DummyHelpElement;
import buildcraft.lib.gui.pos.GuiRectangle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.fluids.FluidType;
import buildcraft.lib.gui.help.GuiHelpUtil;

public class GuiTank extends GuiBC8<ContainerTank> {
    private static final ResourceLocation TEXTURE_BASE = ResourceLocation.parse("buildcraftfactory:textures/gui/tank.png");
    private static final int SIZE_X = 176;
    private static final int SIZE_Y = 181;
    private static final int TANK_X = 80;
    private static final int TANK_Y = 18;
    private static final int TANK_WIDTH = 16;
    private static final int TANK_HEIGHT = 64;
    private static final GuiIcon ICON_GUI = new GuiIcon(TEXTURE_BASE, 0, 0, SIZE_X, SIZE_Y);

    private final TankComponent tankComponent = new TankComponent(
        TANK_X, TANK_Y, TANK_WIDTH, TANK_HEIGHT,
        16 * FluidType.BUCKET_VOLUME,
        176, 0,
        2
    );

    public GuiTank(ContainerTank menu, Inventory inventory, Component title) {
        super(menu, inventory, title, SIZE_X, SIZE_Y);
        inventoryLabelX = 8;
        inventoryLabelY = SIZE_Y - 96;

        tankComponent.setDataoffset(0);
        tankComponent.setup(this, menu.data);

        GuiHelpUtil.addRoot(mainGui, 80, 18, 16, 64, "buildcraft.help.tank.slot.title", 0xFF_55_BB_DD, "buildcraft.help.tank.slot.desc");

        if (menu.tile != null) {
            mainGui.shownElements.add(new DummyHelpElement(
                new GuiRectangle(80, 18, 16, 64).offset(mainGui.rootElement).expand(4),
                menu.tile.tank.helpInfo
            ));
        }
    }

    @Override
    protected void drawBackgroundLayer(PoseStack pose, int mouseX, int mouseY, float partialTicks) {
        GuiGraphicsExtractor guiGraphics = getActiveGraphics();
        ICON_GUI.drawAt(guiGraphics, mainGui.rootElement);
        if (!renderStackAwareFluid(guiGraphics, partialTicks)) {
            tankComponent.render(guiGraphics, mouseX, mouseY, partialTicks, this);
        }
        tankComponent.postRender(guiGraphics, mouseX, mouseY, partialTicks, this);
    }

    @Override
    protected void drawForegroundLayer(PoseStack pose, int mouseX, int mouseY) {
        GuiGraphicsExtractor guiGraphics = getActiveGraphics();
        int rootX = (int) mainGui.rootElement.getX();
        int rootY = (int) mainGui.rootElement.getY();
        guiGraphics.text(font, title, rootX + (imageWidth - font.width(title)) / 2, rootY + 6, 0xFF404040, false);
        guiGraphics.text(font, playerInventoryTitle, rootX + inventoryLabelX, rootY + inventoryLabelY, 0xFF404040, false);
    }

    @Override
    protected void extractTooltip(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        if (!renderStackAwareTooltip(guiGraphics, mouseX, mouseY)) {
            tankComponent.renderTooltip(guiGraphics, mouseX, mouseY);
        }
        super.extractTooltip(guiGraphics, mouseX, mouseY);
    }

    private boolean renderStackAwareFluid(GuiGraphicsExtractor guiGraphics, float partialTicks) {
        FluidStack fluid = columnFluid(partialTicks);
        if (fluid.isEmpty()) {
            return false;
        }
        int capacity = tankCapacity();
        int amount = Math.max(0, menu.getFluidAmount());
        if (amount <= 0 || capacity <= 0) {
            return false;
        }
        int filled = Math.min(TANK_HEIGHT, Math.max(1, (int) ((long) TANK_HEIGHT * amount / capacity)));
        int left = (int) mainGui.rootElement.getX() + TANK_X;
        int top = (int) mainGui.rootElement.getY() + TANK_Y;
        // The region already represents the interpolated fill height, so render it at a 1:1 fill ratio.
        GuiUtil.drawFluid(guiGraphics,
            new GuiRectangle(left, top + TANK_HEIGHT - filled, TANK_WIDTH, filled), fluid, 1, 1);
        return true;
    }

    private boolean renderStackAwareTooltip(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY) {
        if (!isTankHovered(mouseX, mouseY)) {
            return false;
        }
        FluidStack fluid = columnFluid(1.0F);
        if (fluid.isEmpty()) {
            return false;
        }
        int amount = Math.max(0, menu.getFluidAmount());
        guiGraphics.renderComponentTooltip(font, List.of(
            FluidDisplayHelper.getDisplayName(fluid),
            LocaleUtil.localizeFluidStaticAmount(amount, tankCapacity()).withStyle(ChatFormatting.GRAY)
        ), mouseX, mouseY);
        return true;
    }

    private FluidStack columnFluid(float partialTicks) {
        if (menu.tile == null) {
            return FluidStack.EMPTY;
        }
        for (var tankTile : menu.tile.getConnectedTanks()) {
            FluidStackInterp state = tankTile.getFluidForRender(partialTicks);
            if (state != null && state.fluid != null && !state.fluid.isEmpty()) {
                return state.fluid;
            }
        }
        return FluidStack.EMPTY;
    }

    private int tankCapacity() {
        int synced = menu.getTankCapacity();
        return synced > 0 ? synced : 16 * FluidType.BUCKET_VOLUME;
    }

    private boolean isTankHovered(int mouseX, int mouseY) {
        int left = (int) mainGui.rootElement.getX() + TANK_X;
        int top = (int) mainGui.rootElement.getY() + TANK_Y;
        return mouseX >= left && mouseX < left + TANK_WIDTH
            && mouseY >= top && mouseY < top + TANK_HEIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (tankComponent.onClick(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        tankComponent.mouseRelease(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        tankComponent.onClose();
        super.onClose();
    }
}
