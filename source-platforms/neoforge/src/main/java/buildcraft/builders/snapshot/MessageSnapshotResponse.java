/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import buildcraft.lib.internal.debug.BCLog;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import buildcraft.lib.net.BCPacketContext;

public class MessageSnapshotResponse {
    private static final int MAX_COMPRESSED_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private static final long MAX_UNCOMPRESSED_SNAPSHOT_BYTES = 64L * 1024 * 1024;

    private Snapshot snapshot;

    public MessageSnapshotResponse(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    Snapshot getSnapshot() {
        return snapshot;
    }

    public void toBytes(FriendlyByteBuf buf) {
        int before = buf.writerIndex();
        try {
            NbtIo.writeCompressed(Snapshot.writeToNBT(snapshot), new ByteBufOutputStream(buf));
        } catch (IOException | RuntimeException e) {
            throw new EncoderException("Failed to encode BuildCraft snapshot", e);
        }
        int written = buf.writerIndex() - before;
        if (written > MAX_COMPRESSED_SNAPSHOT_BYTES) {
            throw new EncoderException("BuildCraft snapshot payload is too large: " + written);
        }
    }

    public MessageSnapshotResponse(FriendlyByteBuf buf) {
        int readable = buf.readableBytes();
        if (readable <= 0 || readable > MAX_COMPRESSED_SNAPSHOT_BYTES) {
            throw new DecoderException("Invalid BuildCraft snapshot payload size: " + readable);
        }
        try {
            snapshot = Snapshot.readFromNBT(NbtIo.readCompressed(
                new ByteBufInputStream(buf),
                NbtAccounter.create(MAX_UNCOMPRESSED_SNAPSHOT_BYTES)
            ));
        } catch (IOException | RuntimeException e) {
            throw new DecoderException("Failed to decode BuildCraft snapshot", e);
        }
    }

    public static final BiConsumer<MessageSnapshotResponse, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        BCPacketContext context = ctx.get();
        context.enqueueWork(() -> {
            if (FMLEnvironment.dist == Dist.CLIENT) {
                try {
                    ClientAccess.handle(message);
                } catch (RuntimeException e) {
                    BCLog.logger.warn("Dropped invalid snapshot response packet", e);
                }
            }
        });
    };

    @OnlyIn(Dist.CLIENT)
    private static final class ClientAccess {
        private static void handle(MessageSnapshotResponse message) {
            MessageSnapshotResponseClientHandler.handle(message);
        }
    }
}
