/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.gui;

import java.io.IOException;

import buildcraft.lib.net.IPayloadReceiver;
import buildcraft.lib.net.IPayloadWriter;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

/** Defines some sort of separate element that exists on both the server and client. Doesn't draw directly. */
public abstract class Widget_Neptune<C extends MenuBC_Neptune> implements IPayloadReceiver {
    public final C container;

    public Widget_Neptune(C container) {
        this.container = container;
    }

    public boolean isRemote() {
        return container.playerInventory.player.level().isClientSide;
    }

    // Net updating

    protected final void sendWidgetData(IPayloadWriter writer) {
        container.sendWidgetData(this, writer);
    }

    public void handleWidgetDataServer(BCPacketContext ctx, FriendlyByteBuf buffer) throws IOException {
    }
    public void handleWidgetDataClient(BCPacketContext ctx, FriendlyByteBuf buffer) throws IOException {
    }

    @Override
    public void receivePayload(BCPacketContext ctx, FriendlyByteBuf buffer) throws IOException {
        if (ctx.side() == BCNetworkSide.CLIENT) {
            handleWidgetDataClient(ctx, buffer);
        } else {
            handleWidgetDataServer(ctx, buffer);
        }
    }
}
