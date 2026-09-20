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
import buildcraft.lib.tile.TileBC_Neptune;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import buildcraft.lib.net.BCNetworkSide;
import buildcraft.lib.net.BCPacketContext;

public class MessageUpdateTile {
    private static final int MAX_PAYLOAD_SIZE = 64 * 1024;
    private static final double MAX_INTERACTION_DISTANCE_SQR = 64.0D;

    private BlockPos pos;
    private byte[] payload;

    @SuppressWarnings("unused")
    public MessageUpdateTile() {}

    /** Takes ownership of {@code payload}. */
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
        int length = msg.payload.length;
        if (length > MAX_PAYLOAD_SIZE) {
            throw new IllegalStateException("Tile update payload is too large: " + length);
        }
        buf.writeMedium(length);
        buf.writeBytes(msg.payload);
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

    public static final BiConsumer<MessageUpdateTile, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> {
            FriendlyByteBuf payloadBuffer = null;
            try {
                BCNetworkSide side = context.side();
                Level level;
                ServerPlayer sender = context.getSender();

                if (side == BCNetworkSide.SERVER) {
                    if (sender == null) {
                        return;
                    }
                    //? if <1.20 {
                    level = sender.level;
                    //?} else {
                    /*?
                    level = sender.level();
                    ?*/
                    //?}
                    if (!level.hasChunkAt(message.pos)) {
                        return;
                    }
                } else {
                    level = net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()
                        ? MessageUpdateTileClientHandler.getClientLevel()
                        : null;
                    if (level == null) {
                        return;
                    }
                }

                BlockEntity tile = level.getBlockEntity(message.pos);
                if (!(tile instanceof IPayloadReceiver receiver)) {
                    // Missing client-side tiles can indicate a legitimate synchronization race. Do not let a
                    // malicious client turn invalid server-bound positions into an unbounded warning stream.
                    if (side == BCNetworkSide.CLIENT) {
                        BCLog.logger.warn("Dropped BuildCraft tile update for missing/incompatible tile at " + message.pos);
                    }
                    return;
                }

                if (side == BCNetworkSide.SERVER) {
                    // Client-originated tile messages are interaction packets, not arbitrary remote-control packets.
                    // Reuse the tile's normal permission and distance checks when possible.
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
            } catch (IOException | RuntimeException io) {
                // Invalid server-bound payloads are untrusted input. Keep them out of the normal warning log so a
                // modified client cannot flood the server with stack traces; client-side sync failures remain visible.
                if (context.side() == BCNetworkSide.CLIENT) {
                    BCLog.logger.warn("Dropped invalid BuildCraft tile update packet", io);
                } else {
                    BCLog.logger.debug("Dropped invalid client BuildCraft tile update packet", io);
                }
            } finally {
                if (payloadBuffer != null) {
                    payloadBuffer.release();
                }
            }
        });
        context.setPacketHandled(true);
    };
}
