//? source if >=1.21.1
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.widget;

import java.io.IOException;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;

import buildcraft.lib.fluid.Tank;
import buildcraft.lib.gui.BuildCraftGui;
import buildcraft.lib.gui.GuiElementSimple;
import buildcraft.lib.gui.GuiIcon;
import buildcraft.lib.gui.IGuiElement;
import buildcraft.lib.gui.IInteractionElement;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.Widget_Neptune;
import buildcraft.lib.gui.elem.ToolTip;
import buildcraft.lib.gui.help.ElementHelpInfo.HelpPosition;
import buildcraft.lib.gui.pos.IGuiArea;
import buildcraft.lib.misc.GuiUtil;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCPacketContext;
import buildcraft.lib.compat.RenderCompat;

public class WidgetFluidTank extends Widget_Neptune<MenuBC_Neptune> {
    private static final byte NET_CLICK = 0;

    private final Tank tank;
    public final boolean isClientSide;

    public WidgetFluidTank(MenuBC_Neptune container, Tank tank) {
        super(container);
        this.tank = tank;
        isClientSide = false;
    }
    

    public void handleWidgetDataServer(BCPacketContext ctx, FriendlyByteBuf buffer) throws IOException {
        byte id = buffer.readByte();
        if (id == NET_CLICK) {
            tank.onGuiClicked(container);
        }
    }
    public IGuiElement createGuiElement(BuildCraftGui gui, IGuiArea area, GuiIcon overlay) {
        return new GuiElementFluidTank(gui, area, overlay);
    }

    private final class GuiElementFluidTank extends GuiElementSimple implements IInteractionElement {
        private final GuiIcon overlay;

        public GuiElementFluidTank(BuildCraftGui gui, IGuiArea area, GuiIcon overlay) {
            super(gui, area);
            this.overlay = overlay;
        }

        public void drawBackground(GuiGraphics guiGraphics, float partialTicks) {
        	GuiUtil.drawFluid(guiGraphics, this, tank);
            if (overlay != null) {
                overlay.drawCutInside(guiGraphics, this);
            }
        }

        public void onMouseClicked(int button) {
            if (contains(gui.mouse)) {
                WidgetFluidTank.this.sendWidgetData(buffer -> buffer.writeByte(NET_CLICK));
            }
        }

        public void addToolTips(List<ToolTip> tooltips) {
            if (contains(gui.mouse)) {
                ToolTip tooltip = tank.getToolTip();
                tooltip.refresh();
                tooltips.add(tooltip);
            }
        }

        public void addHelpElements(List<HelpPosition> elements) {
        	elements.add(tank.helpInfo.target(this.expand(4)));
        }
    }
}
