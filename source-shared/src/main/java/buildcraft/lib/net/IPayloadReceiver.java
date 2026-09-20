/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib.net;

import java.io.IOException;

import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCPacketContext;

public interface IPayloadReceiver {
    void receivePayload(BCPacketContext ctx, FriendlyByteBuf buffer) throws IOException;
}
