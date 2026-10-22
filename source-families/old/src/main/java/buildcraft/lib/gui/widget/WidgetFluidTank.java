/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui.widget;

import java.io.IOException;

import buildcraft.lib.fluid.Tank;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.gui.Widget_Neptune;
import buildcraft.lib.net.BCPacketContext;
import net.minecraft.network.FriendlyByteBuf;

public class WidgetFluidTank extends Widget_Neptune<MenuBC_Neptune> {
    private static final byte NET_CLICK = 0;

    private final Tank tank;
    public final boolean isClientSide;

    public WidgetFluidTank(MenuBC_Neptune container, Tank tank) {
        super(container);
        this.tank = tank;
        isClientSide = false;
    }

    @Override
    public void handleWidgetDataServer(BCPacketContext ctx, FriendlyByteBuf buffer) throws IOException {
        byte id = buffer.readByte();
        if (id == NET_CLICK) {
            tank.onGuiClicked(container);
        }
    }

    Tank getTank() {
        return tank;
    }

    void sendClick() {
        sendWidgetData(buffer -> buffer.writeByte(NET_CLICK));
    }
}
