/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.net.BCNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
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
                // Resolve through the requesting player's server level. The generic SERVER singleton may still
                // point at a previously visited integrated world, which makes a valid world-local blueprint look
                // missing (or exposes the wrong world's copy).
                Snapshot snapshot = GlobalSavedDataSnapshots.getSnapshotForConstruction(player.serverLevel(), message.key);
                if (snapshot != null) {
                    Snapshot transientSnapshot = snapshot.copy();
                    transientSnapshot.key = new Snapshot.Key(transientSnapshot.key, (Snapshot.Header) null);
                    BCNetwork.sendTo(new MessageSnapshotResponse(transientSnapshot), player);
                }
            } catch (RuntimeException e) {
                BCLog.logger.debug("Dropped invalid snapshot request packet: {}", e.toString());
            }
        });
    };
}
