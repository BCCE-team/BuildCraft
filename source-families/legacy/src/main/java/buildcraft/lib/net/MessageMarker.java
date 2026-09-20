/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.net;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.misc.MessageUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import buildcraft.lib.net.BCPacketContext;

public class MessageMarker {
    private static final boolean DEBUG = MessageManager.DEBUG;
    private static final int MAX_POSITIONS = 8192;

    public boolean add, multiple, connection;
    public int cacheId, count;
    public final List<BlockPos> positions = new ArrayList<>();

    public MessageMarker() {}
    
    public MessageMarker(FriendlyByteBuf buf) {
        add = buf.readBoolean();
        multiple = buf.readBoolean();
        connection = buf.readBoolean();
        cacheId = buf.readShort();
        if (multiple) {
            count = NetworkSecurity.requireCount(buf.readUnsignedShort(), MAX_POSITIONS, "marker position count");
        } else {
            count = 1;
        }
        
        for (int i = 0; i < count; i++) {
            positions.add(MessageUtil.readBlockPos(buf));
        }
    }

    
    public static void toBytes(MessageMarker msg, FriendlyByteBuf buf) {
        msg.count = msg.positions.size();
        if (msg.count > MAX_POSITIONS) {
            throw new IllegalStateException("Too many marker positions: " + msg.count);
        }
        msg.multiple = msg.count != 1;
        buf.writeBoolean(msg.add);
        buf.writeBoolean(msg.multiple);
        buf.writeBoolean(msg.connection);
        buf.writeShort(msg.cacheId);
        if (msg.multiple) {
            buf.writeShort(msg.count);
        }
        for (BlockPos pos : msg.positions) {
            MessageUtil.writeBlockPos(buf, pos);
        }
    }

    public MessageMarker copy() {
        MessageMarker copy = new MessageMarker();
        copy.add = add;
        copy.multiple = multiple;
        copy.connection = connection;
        copy.cacheId = cacheId;
        copy.count = count;
        copy.positions.addAll(positions);
        return copy;
    }

    @Override
    public String toString() {
        boolean[] flags = { add, multiple, connection };
        return "Message Marker [" + Arrays.toString(flags) + ", cacheId " + cacheId + ", count = " + count
            + ", positions = " + positions + "]";
    }

    public static final BiConsumer<MessageMarker, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        if (context.side() != BCNetworkSide.CLIENT) {
            if (DEBUG) {
                BCLog.logger.warn("[lib.messages][marker] Received invalid marker message from client!");
            }
            context.setPacketHandled(true);
            return;
        }

        // Marker messages mutate the client marker cache read by the render thread. MessageMarker is
        // bidirectional, so MessageManager may bypass its normal
        // client-side wrapper and call this handler directly on Netty's network thread. Always hop to the
        // client main thread here; otherwise connection updates can race with MarkerRenderer and leave a
        // half-reset VolumeConnection/Box, which crashes in LaserBoxRenderer or leaves stale blue signal lasers.
        context.enqueueWork(() -> {
            if (!MessageMarkerClientHandler.handleOrQueue(message)) {
                if (DEBUG) {
                    BCLog.logger.warn("[lib.messages][marker] Queued marker message until the client world is ready: " + message);
                }
            }
        });
        context.setPacketHandled(true);
    };
}
