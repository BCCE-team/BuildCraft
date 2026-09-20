/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.net.cache;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCPacketContext;

public class MessageObjectCacheResponse {

    private static final int MAX_IDS = MessageObjectCacheRequest.MAX_IDS;
    static final int MAX_VALUE_SIZE = 0xFFFF;
    static final int MAX_TOTAL_VALUE_BYTES = 2 * 1024 * 1024;

    private int cacheId;

    private int[] ids;
    private byte[][] values;

    @SuppressWarnings("unused")
    public MessageObjectCacheResponse() {
    }

    MessageObjectCacheResponse(int cacheId, int[] ids, byte[][] values) {
        this.cacheId = cacheId;
        this.ids = ids;
        this.values = values;
    }

    public static void toBytes(MessageObjectCacheResponse msg, ByteBuf buf) {
        buf.writeByte(msg.cacheId);
        buf.writeShort(msg.ids.length);
        int totalBytes = 0;
        for (int i = 0; i < msg.ids.length; i++) {
            if (msg.values[i].length > MAX_VALUE_SIZE) {
                throw new IllegalStateException("Object cache response value is too large: " + msg.values[i].length);
            }
            totalBytes += msg.values[i].length;
            if (totalBytes > MAX_TOTAL_VALUE_BYTES) {
                throw new IllegalStateException("Object cache response is too large: " + totalBytes);
            }
            buf.writeInt(msg.ids[i]);
            buf.writeShort(msg.values[i].length);
            buf.writeBytes(msg.values[i]);
        }
    }

    public MessageObjectCacheResponse(ByteBuf buf) {
        cacheId = buf.readByte();
        int idCount = buf.readUnsignedShort();
        if (idCount > MAX_IDS) {
            throw new DecoderException("Invalid object cache response count: " + idCount);
        }
        ids = new int[idCount];
        values = new byte[idCount][];
        int totalBytes = 0;
        for (int i = 0; i < idCount; i++) {
            if (buf.readableBytes() < Integer.BYTES + Short.BYTES) {
                throw new DecoderException("Truncated object cache response header");
            }
            ids[i] = buf.readInt();
            int valueSize = buf.readUnsignedShort();
            if (valueSize > MAX_VALUE_SIZE || valueSize > buf.readableBytes()) {
                throw new DecoderException("Invalid object cache response value size: " + valueSize
                    + " readable=" + buf.readableBytes());
            }
            totalBytes += valueSize;
            if (totalBytes > MAX_TOTAL_VALUE_BYTES) {
                throw new DecoderException("Object cache response exceeds total byte limit: " + totalBytes);
            }
            values[i] = new byte[valueSize];
            buf.readBytes(values[i]);
        }
    }

    public static final BiConsumer<MessageObjectCacheResponse, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> {
            try {
                if (message.cacheId < 0 || message.cacheId >= BuildCraftObjectCaches.CACHES.size()) {
                    BCLog.logger.warn("Dropped object cache response with invalid cache id {}", message.cacheId);
                    return;
                }
                NetworkedObjectCache<?> cache = BuildCraftObjectCaches.CACHES.get(message.cacheId);
                for (int i = 0; i < message.ids.length; i++) {
                    int id = message.ids[i];
                    byte[] payload = message.values[i];
                    FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
                    try {
                        cache.readObjectClient(id, buffer);
                    } finally {
                        buffer.release();
                    }
                }
            } catch (IOException | RuntimeException io) {
                BCLog.logger.warn("Dropped invalid object cache response", io);
            }
        });
        context.setPacketHandled(true);
    };
}
