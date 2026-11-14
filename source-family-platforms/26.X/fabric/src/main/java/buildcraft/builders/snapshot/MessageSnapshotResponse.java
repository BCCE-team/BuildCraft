/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;

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
            // NbtIo.readCompressed() finishes as soon as the root compound has been
            // decoded. With a network ByteBufInputStream this can leave the final
            // GZIP trailer (typically four bytes) unread, and MessageManager then
            // correctly rejects the ostensibly malformed message. Keep the wire
            // format unchanged, but finish the *same* GZIP stream before returning.
            try (DataInputStream input = new DataInputStream(
                    new GZIPInputStream(new ByteBufInputStream(buf)))) {
                var nbt = NbtIo.read(input, NbtAccounter.create(MAX_UNCOMPRESSED_SNAPSHOT_BYTES));
                // Reading to EOF validates the checksum/trailer. A second NBT root
                // or other decompressed data must NOT be silently accepted.
                if (input.read() != -1) {
                    throw new DecoderException("Trailing decompressed data in BuildCraft snapshot");
                }
                snapshot = Snapshot.readFromNBT(nbt);
            }
        } catch (IOException | RuntimeException e) {
            throw new DecoderException("Failed to decode BuildCraft snapshot", e);
        }
    }

}
