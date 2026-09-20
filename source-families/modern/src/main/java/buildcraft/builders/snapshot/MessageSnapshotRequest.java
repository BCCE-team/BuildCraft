/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.net.MessageManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class MessageSnapshotRequest{
    private Snapshot.Key key;

    public MessageSnapshotRequest(Snapshot.Key key) {
        this.key = key;
    }

    public void toBytes(FriendlyByteBuf buf) {
        key.writeToByteBuf((buf));
    }

    public MessageSnapshotRequest(FriendlyByteBuf buf) {
        key = new Snapshot.Key((buf));
    }

    public static final BiConsumer<MessageSnapshotRequest, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> {
            try {
                if (!(context.player() instanceof ServerPlayer player) || !SnapshotRequestLimiter.allow(player)) {
                    return;
                }
                Snapshot snapshot = GlobalSavedDataSnapshots.get(BCNetworkSide.SERVER).getSnapshot(message.key);
                if (snapshot != null) {
                    MessageManager.sendTo(new MessageSnapshotResponse(snapshot), player);
                }
            } catch (RuntimeException e) {
                BCLog.logger.debug("Dropped invalid snapshot request packet: {}", e.toString());
            }
        });
    };
}
