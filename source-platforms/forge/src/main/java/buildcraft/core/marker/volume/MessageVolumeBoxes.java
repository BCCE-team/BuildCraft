/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team This Source Code Form is subject to the terms of the Mozilla
 * Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/
 */

package buildcraft.core.marker.volume;

import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import buildcraft.lib.net.MessageManager;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import buildcraft.lib.net.BCPacketContext;

public class MessageVolumeBoxes {
    private static final int MAX_BOXES = 4096;
    private static final int BATCH_BOXES = 128;
    private static final int BATCH_BYTES = 512 * 1024;
    private static final int MAX_BOX_BYTES = 1024 * 1024;

    final boolean replaceAll;
    final List<UUID> removedIds;
    final List<byte[]> buffers;

    public MessageVolumeBoxes() {
        replaceAll = true;
        removedIds = Collections.emptyList();
        buffers = Collections.emptyList();
    }

    public MessageVolumeBoxes(FriendlyByteBuf buf) {
        replaceAll = buf.readBoolean();
        int count = buf.readInt();
        if (count < 0 || count > MAX_BOXES) {
            throw new DecoderException("Invalid volume box count: " + count);
        }
        List<byte[]> decoded = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int bytes = buf.readVarInt();
            if (bytes < 0 || bytes > MAX_BOX_BYTES || bytes > buf.readableBytes()) {
                throw new DecoderException("Invalid volume box payload size: " + bytes
                    + " readable=" + buf.readableBytes());
            }
            byte[] payload = new byte[bytes];
            buf.readBytes(payload);
            decoded.add(payload);
        }
        buffers = ImmutableList.copyOf(decoded);

        int removedCount = buf.readVarInt();
        if (removedCount < 0 || removedCount > MAX_BOXES) {
            throw new DecoderException("Invalid removed volume box count: " + removedCount);
        }
        List<UUID> removed = new ArrayList<>(removedCount);
        for (int i = 0; i < removedCount; i++) {
            removed.add(buf.readUUID());
        }
        removedIds = ImmutableList.copyOf(removed);
    }

    /** Full snapshot, used for the first sync and when the set of players in a dimension changes. */
    public MessageVolumeBoxes(List<VolumeBox> volumeBoxes) {
        this(true, volumeBoxes, Collections.emptyList());
    }

    public static MessageVolumeBoxes delta(List<VolumeBox> changed, List<UUID> removedIds) {
        return new MessageVolumeBoxes(false, changed, removedIds);
    }

    private MessageVolumeBoxes(boolean replaceAll, List<VolumeBox> volumeBoxes, List<UUID> removedIds) {
        this.replaceAll = replaceAll;
        this.removedIds = ImmutableList.copyOf(removedIds);

        if (volumeBoxes.size() > MAX_BOXES || removedIds.size() > MAX_BOXES) {
            throw new IllegalArgumentException("Use batched synchronization for large Volume Box sets");
        }
        List<byte[]> encoded = new ArrayList<>(volumeBoxes.size());
        for (VolumeBox volumeBox : volumeBoxes) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                volumeBox.toBytes(buffer);
                int bytes = buffer.readableBytes();
                if (bytes > MAX_BOX_BYTES) {
                    throw new IllegalStateException("Volume box payload is too large: " + bytes);
                }
                byte[] payload = new byte[bytes];
                buffer.getBytes(buffer.readerIndex(), payload);
                encoded.add(payload);
            } finally {
                buffer.release();
            }
        }
        this.buffers = ImmutableList.copyOf(encoded);
    }

    /** Send a complete index without losing boxes beyond the old 4096-entry protocol limit. */
    public static void sendSnapshotToDimension(List<VolumeBox> boxes, ResourceKey<Level> dimension) {
        sendBatched(boxes, List.of(), true, dimension);
    }

    public static void sendDeltaToDimension(List<VolumeBox> changed, List<UUID> removed,
            ResourceKey<Level> dimension) {
        sendBatched(changed, removed, false, dimension);
    }

    private static void sendBatched(List<VolumeBox> boxes, List<UUID> removed,
            boolean snapshot, ResourceKey<Level> dimension) {
        List<VolumeBox> batch = new ArrayList<>();
        int used = 0;
        boolean first = true;
        for (VolumeBox box : boxes) {
            FriendlyByteBuf tmp = new FriendlyByteBuf(Unpooled.buffer());
            int size;
            try {
                box.toBytes(tmp);
                size = tmp.readableBytes() + 5;
            } finally {
                tmp.release();
            }
            if (!batch.isEmpty() && (batch.size() >= BATCH_BOXES || used + size > BATCH_BYTES)) {
                MessageManager.sendToDimension(new MessageVolumeBoxes(snapshot && first, batch, List.of()), dimension);
                first = false;
                batch.clear();
                used = 0;
            }
            batch.add(box);
            used += size;
        }
        if (!batch.isEmpty() || (snapshot && first)) {
            MessageManager.sendToDimension(new MessageVolumeBoxes(snapshot && first, batch, List.of()), dimension);
            first = false;
        }
        // Removal deltas are naturally small; splitting is still necessary for mass edits.
        for (int offset = 0; offset < removed.size(); offset += BATCH_BOXES) {
            MessageManager.sendToDimension(new MessageVolumeBoxes(false, List.of(),
                removed.subList(offset, Math.min(offset + BATCH_BOXES, removed.size()))), dimension);
        }
    }

    public static void toBytes(MessageVolumeBoxes msg, FriendlyByteBuf buf) {
        if (msg.buffers.size() > MAX_BOXES || msg.removedIds.size() > MAX_BOXES) {
            throw new IllegalStateException("Too many volume box updates");
        }
        buf.writeBoolean(msg.replaceAll);
        buf.writeInt(msg.buffers.size());
        for (byte[] localBuffer : msg.buffers) {
            int bytes = localBuffer.length;
            if (bytes > MAX_BOX_BYTES) {
                throw new IllegalStateException("Volume box payload is too large: " + bytes);
            }
            buf.writeVarInt(bytes);
            buf.writeBytes(localBuffer);
        }
        buf.writeVarInt(msg.removedIds.size());
        msg.removedIds.forEach(buf::writeUUID);
    }

    public static final BiConsumer<MessageVolumeBoxes, Supplier<BCPacketContext>> HANDLER = (message, ctx) -> {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MessageVolumeBoxesClientHandler.handle(message, ctx));
        ctx.get().setPacketHandled(true);
    };
}
