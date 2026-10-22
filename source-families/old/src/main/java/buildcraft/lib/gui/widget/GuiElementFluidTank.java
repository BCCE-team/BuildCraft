/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.widget;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
//? if >=1.20 {
/*?
import net.minecraft.client.gui.GuiGraphics;
?*/
//?}

import buildcraft.lib.fluid.Tank;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiElementSimple;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.IInteractionElement;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.gui.help.ElementHelpInfo.HelpPosition;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.misc.GuiUtil;

public final class GuiElementFluidTank extends GuiElementSimple implements IInteractionElement {
    private final WidgetFluidTank widget;
    private final GuiIcon overlay;

    public GuiElementFluidTank(WidgetFluidTank widget, BuildCraftGui gui, IGuiArea area, GuiIcon overlay) {
        super(gui, area);
        this.widget = widget;
        this.overlay = overlay;
    }

    @Override
    //? if <1.20 {
    public void drawBackground(PoseStack pose, float partialTicks) {
        GuiUtil.drawFluid(pose, this, widget.getTank());
    //?} else {
    /*?
    public void drawBackground(GuiGraphics guiGraphics, float partialTicks) {
        PoseStack pose = guiGraphics.pose();
        GuiUtil.drawFluid(guiGraphics, this, widget.getTank());
    ?*/
    //?}
        if (overlay != null) {
            //? if <1.20 {
            overlay.drawCutInside(pose, this);
            //?} else {
            /*?
            overlay.drawCutInside(guiGraphics, this);
            ?*/
            //?}
        }
    }

    @Override
    public void onMouseClicked(int button) {
        if (contains(gui.mouse)) {
            widget.sendClick();
        }
    }

    @Override
    public void addToolTips(List<ToolTip> tooltips) {
        if (contains(gui.mouse)) {
            Tank tank = widget.getTank();
            ToolTip tooltip = tank.getToolTip();
            tooltip.refresh();
            tooltips.add(tooltip);
        }
    }

    @Override
    public void addHelpElements(List<HelpPosition> elements) {
        elements.add(widget.getTank().helpInfo.target(this.expand(4)));
    }
}
