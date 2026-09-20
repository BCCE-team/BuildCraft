/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics.zone;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCPacketContext;

public class MessageZoneMapResponse {
    private ZonePlannerMapChunkKey key;
    private ZonePlannerMapChunk data;

    @SuppressWarnings("unused")
    public MessageZoneMapResponse() {
    }

    public MessageZoneMapResponse(ZonePlannerMapChunkKey zonePlannerMapChunkKey, ZonePlannerMapChunk data) {
        this.key = zonePlannerMapChunkKey;
        this.data = data;
    }

    public MessageZoneMapResponse(FriendlyByteBuf buf) {
        key = new ZonePlannerMapChunkKey(buf);
        data = new ZonePlannerMapChunk(buf);
    }

    public void toBytes(FriendlyByteBuf buf) {
        key.toBytes(buf);
        data.write(buf);
    }

    public static final BiConsumer<MessageZoneMapResponse, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        ctx.get().enqueueWork(() -> ZonePlannerMapDataClient.INSTANCE.onChunkReceived(message.key, message.data));
        ctx.get().setPacketHandled(true);
    };
}
