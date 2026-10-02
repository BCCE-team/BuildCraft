/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.net;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.gui.MenuBC_Neptune;
import buildcraft.lib.misc.MessageUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class MessageContainer {

    private static final int MAX_PAYLOAD_SIZE = 32 * 1024;

    private int windowId;
    private int msgId;
    private byte[] payload;

    @SuppressWarnings("unused")
    public MessageContainer() {}

    /** Takes ownership of {@code payload} and releases it after copying. */
    public MessageContainer(int windowId, int msgId, FriendlyByteBuf payload) {
        this.windowId = windowId;
        this.msgId = msgId;
        this.payload = copyAndRelease(payload);
        if (this.payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalStateException("Container packet payload is too large: " + this.payload.length);
        }
    }

    public MessageContainer(FriendlyByteBuf buf) {
        windowId = buf.readInt();
        msgId = buf.readUnsignedShort();
        int payloadSize = buf.readUnsignedShort();
        if (payloadSize > MAX_PAYLOAD_SIZE || payloadSize > buf.readableBytes()) {
            throw new DecoderException("Invalid container packet payload size: " + payloadSize
                + " readable=" + buf.readableBytes());
        }
        payload = new byte[payloadSize];
        buf.readBytes(payload);
    }

    public static void toBytes(MessageContainer msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.windowId);
        buf.writeShort(msg.msgId);
        int length = msg.payload == null ? 0 : msg.payload.length;
        if (length > MAX_PAYLOAD_SIZE) {
            throw new IllegalStateException("Container packet payload is too large: " + length);
        }
        buf.writeShort(length);
        if (length > 0) {
            buf.writeBytes(msg.payload);
        }
    }

    private static byte[] copyAndRelease(FriendlyByteBuf source) {
        if (source == null) {
            return new byte[0];
        }
        try {
            byte[] bytes = new byte[source.readableBytes()];
            source.getBytes(source.readerIndex(), bytes);
            return bytes;
        } finally {
            source.release();
        }
    }

    public static final BiConsumer<MessageContainer, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> {
            FriendlyByteBuf payloadBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(message.payload));
            try {
                int id = message.windowId;
                Player player = context.player();
                BCNetworkSide side = context.side() == BCNetworkSide.CLIENT
                    ? BCNetworkSide.CLIENT
                    : BCNetworkSide.SERVER;
                if (side == BCNetworkSide.SERVER && player != null && player.containerMenu.containerId == id
                    && player.containerMenu instanceof MenuBC_Neptune container) {
                    if (player.isSpectator()) {
                        return;
                    }
                    if (!container.stillValid(player) || !container.getIdAllocator().isAllocated(message.msgId)) {
                        return;
                    }
                    container.readMessage(message.msgId, payloadBuffer, side, context);
                    NetworkSecurity.requireFullyRead(payloadBuffer, container.getClass().getName() + "#"
                        + container.getIdAllocator().getNameFor(message.msgId));
                } else if (side == BCNetworkSide.CLIENT
                    && MessageContainerClientHandler.getClientContainerMenu() instanceof MenuBC_Neptune container) {
                    if (!container.getIdAllocator().isAllocated(message.msgId)) {
                        throw new DecoderException("Unknown client container message id " + message.msgId);
                    }
                    container.readMessage(message.msgId, payloadBuffer, side, context);
                    String extra = container.getClass() + ", id = "
                        + container.getIdAllocator().getNameFor(message.msgId);
                    MessageUtil.ensureEmpty(payloadBuffer, true, extra);
                }
            } catch (IOException | RuntimeException e) {
                if (context.side() == BCNetworkSide.CLIENT) {
                    BCLog.logger.warn("Dropped invalid BuildCraft container packet", e);
                } else {
                    BCLog.logger.debug("Dropped invalid client BuildCraft container packet", e);
                }
            } finally {
                payloadBuffer.release();
            }
        });
    };
}
