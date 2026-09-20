/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.net;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.tile.TileBC_Neptune;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCPacketContext;

public class MessageUpdateTile {
    private static final int MAX_PAYLOAD_SIZE = 64 * 1024;
    private static final double MAX_INTERACTION_DISTANCE_SQR = 64.0D;

    private BlockPos pos;
    private byte[] payload;

    @SuppressWarnings("unused")
    public MessageUpdateTile() {}

    /** Takes ownership of {@code payload} and releases it after copying. */
    public MessageUpdateTile(BlockPos pos, FriendlyByteBuf payload) {
        this.pos = pos;
        this.payload = copyAndRelease(payload);
        if (getPayloadSize() > MAX_PAYLOAD_SIZE) {
            throw new IllegalStateException("Can't write out " + getPayloadSize() + " bytes!");
        }
    }

    public int getPayloadSize() {
        return payload == null ? 0 : payload.length;
    }

    public MessageUpdateTile(FriendlyByteBuf buf) {
        pos = buf.readBlockPos();
        int size = buf.readUnsignedMedium();
        if (size > MAX_PAYLOAD_SIZE || size > buf.readableBytes()) {
            throw new DecoderException("Invalid tile update payload size: " + size + " readable=" + buf.readableBytes());
        }
        payload = new byte[size];
        buf.readBytes(payload);
    }

    public static void toBytes(MessageUpdateTile msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        int length = msg.payload == null ? 0 : msg.payload.length;
        if (length > MAX_PAYLOAD_SIZE) {
            throw new IllegalStateException("Tile update payload is too large: " + length);
        }
        buf.writeMedium(length);
        if (length > 0) {
            buf.writeBytes(msg.payload);
        }
    }

    private static byte[] copyAndRelease(FriendlyByteBuf source) {
        if (source == null) return new byte[0];
        try {
            byte[] bytes = new byte[source.readableBytes()];
            source.getBytes(source.readerIndex(), bytes);
            return bytes;
        } finally {
            source.release();
        }
    }

    public static final BiConsumer<MessageUpdateTile, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> handle(message, context));
    };

    private static void handle(MessageUpdateTile message, BCPacketContext context) {
        FriendlyByteBuf payloadBuffer = null;
        boolean clientSide = context.side() == BCNetworkSide.CLIENT;
        try {
            if (context.player() == null) {
                return;
            }
            Level level = context.player().level();
            if (!level.hasChunkAt(message.pos)) {
                return;
            }

            BlockEntity tile = level.getBlockEntity(message.pos);
            if (!(tile instanceof IPayloadReceiver receiver)) {
                if (clientSide) {
                    BCLog.logger.warn("Dropped BuildCraft tile update for missing/incompatible tile at {}", message.pos);
                }
                return;
            }

            if (!clientSide) {
                if (!(context.player() instanceof ServerPlayer sender)) {
                    return;
                }
                if (tile instanceof TileBC_Neptune bcTile) {
                    if (!bcTile.canInteractWith(sender)) {
                        return;
                    }
                } else {
                    double x = message.pos.getX() + 0.5D;
                    double y = message.pos.getY() + 0.5D;
                    double z = message.pos.getZ() + 0.5D;
                    if (sender.distanceToSqr(x, y, z) > MAX_INTERACTION_DISTANCE_SQR) {
                        return;
                    }
                }
            }

            payloadBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(message.payload));
            receiver.receivePayload(context, payloadBuffer);
            NetworkSecurity.requireFullyRead(payloadBuffer, tile.getClass().getName());
        } catch (IOException | RuntimeException exception) {
            if (clientSide) {
                BCLog.logger.warn("Dropped invalid BuildCraft tile update packet", exception);
            } else {
                BCLog.logger.debug("Dropped invalid client BuildCraft tile update packet", exception);
            }
        } finally {
            if (payloadBuffer != null) {
                payloadBuffer.release();
            }
        }
    }
}
