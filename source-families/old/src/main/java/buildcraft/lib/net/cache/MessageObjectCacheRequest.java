/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.net.cache;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.net.MessageManager;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import buildcraft.lib.net.BCPacketContext;

/**
 * Signifies a client to server request for the value of a cached object, given
 * its ID.
 */
public class MessageObjectCacheRequest {

    static final int MAX_IDS = 256;
    /** Server-thread-only rate budget, per authenticated player (not per cache). */
    private static final Map<net.minecraft.server.level.ServerPlayer, RequestBudget> REQUEST_BUDGETS = new WeakHashMap<>();
    private static final class RequestBudget {
        long tick;
        int remaining;
        RequestBudget(long tick) { this.tick = tick; this.remaining = 1024; }
    }

    private static boolean allowRequest(net.minecraft.server.level.ServerPlayer sender, int count) {
        if (count <= 0 || count > MAX_IDS) return false;
        long tick = sender.getCommandSenderWorld().getGameTime();
        RequestBudget budget = REQUEST_BUDGETS.computeIfAbsent(sender, p -> new RequestBudget(tick));
        if (tick < budget.tick || tick - budget.tick >= 20) {
            budget.tick = tick;
            budget.remaining = 1024;
        }
        if (budget.remaining < count) return false;
        budget.remaining -= count;
        return true;
    }


    private int cacheId;

    private int[] ids;

    @SuppressWarnings("unused")
    public MessageObjectCacheRequest() {
    }

    MessageObjectCacheRequest(NetworkedObjectCache<?> cache, int[] ids) {
        this.cacheId = BuildCraftObjectCaches.CACHES.indexOf(cache);
        this.ids = ids;
        if (ids.length > MAX_IDS) {
            throw new IllegalStateException("Tried to request too many ID's! (" + ids.length + ")");
        }
    }

    public static void toBytes(MessageObjectCacheRequest msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.cacheId);
        buf.writeShort(msg.ids.length);
        for (int id : msg.ids) {
            buf.writeInt(id);
        }
    }

    public MessageObjectCacheRequest(FriendlyByteBuf buf) {
        cacheId = buf.readByte();
        int idCount = buf.readUnsignedShort();
        if (idCount > MAX_IDS || buf.readableBytes() < idCount * Integer.BYTES) {
            throw new DecoderException("Invalid object cache request count: " + idCount
                + " readable=" + buf.readableBytes());
        }
        ids = new int[idCount];
        for (int i = 0; i < idCount; i++) {
            ids[i] = buf.readInt();
        }
    }

    public static final BiConsumer<MessageObjectCacheRequest, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> {
            if (message.cacheId < 0 || message.cacheId >= BuildCraftObjectCaches.CACHES.size()) {
                BCLog.logger.debug("Dropped object cache request with invalid cache id {}", message.cacheId);
                return;
            }
            net.minecraft.server.level.ServerPlayer sender = context.getSender();
            if (sender == null || !allowRequest(sender, message.ids.length)) {
                return;
            }
            NetworkedObjectCache<?> cache = BuildCraftObjectCaches.CACHES.get(message.cacheId);
            byte[][] values = new byte[message.ids.length][];

            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                int totalBytes = 0;
                for (int i = 0; i < values.length; i++) {
                    int id = message.ids[i];
                    boolean found = cache.writeObjectServer(id, buffer);
                    // Each value has one presence byte: a valid encoded value may itself be empty.
                    int valueSize = found ? buffer.readableBytes() + 1 : 1;
                    if (valueSize > MessageObjectCacheResponse.MAX_VALUE_SIZE) {
                        throw new IllegalStateException("Object cache value is too large: " + valueSize);
                    }
                    totalBytes += valueSize;
                    if (totalBytes > MessageObjectCacheResponse.MAX_TOTAL_VALUE_BYTES) {
                        throw new IllegalStateException("Object cache response is too large: " + totalBytes);
                    }
                    values[i] = new byte[valueSize];
                    values[i][0] = (byte) (found ? 1 : 0);
                    if (found) buffer.readBytes(values[i], 1, valueSize - 1);
                    buffer.clear();
                }
                if (context.getSender() != null) {
                    MessageManager.sendTo(new MessageObjectCacheResponse(message.cacheId, message.ids, values),
                        context.getSender());
                }
            } catch (RuntimeException e) {
                BCLog.logger.debug("Dropped invalid object cache request", e);
            } finally {
                buffer.release();
            }
        });
        context.setPacketHandled(true);
    };
}
