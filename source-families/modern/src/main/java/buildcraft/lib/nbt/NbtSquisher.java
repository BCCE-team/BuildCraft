/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.nbt;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.data.NbtSquishConstants;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

public class NbtSquisher {
//    public static final ProfilerFiller profiler = new ProfilerFiller();
    /** Used by testing classes to replace ByteBuf instances with PrintingByteBuf -- but we don't have that
     * class in main because it makes checkstyle complain. */
    public static Function<ByteBuf, FriendlyByteBuf> debugBuffer = null;

    private static final int TYPE_MC_GZIP = NbtSquishConstants.VANILLA_COMPRESSED;
    private static final int TYPE_MC = NbtSquishConstants.VANILLA;
    private static final int TYPE_BC_1_GZIP = NbtSquishConstants.BUILDCRAFT_V1_COMPRESSED;
    private static final int TYPE_BC_1 = NbtSquishConstants.BUILDCRAFT_V1;

    public static byte[] squish(CompoundTag nbt, int type) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            squish(nbt, type, baos);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write to a perfectly good ByteArrayOutputStream", e);
        }
        return baos.toByteArray();
    }

    public static void squish(CompoundTag nbt, int type, ByteBuf buf) {
        try (ByteBufOutputStream bbos = new ByteBufOutputStream(buf)) {
            squish(nbt, type, bbos);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write to a perfectly good ByteBufOutputStream", e);
        }
    }

    public static void squish(CompoundTag nbt, int type, OutputStream stream) throws IOException {
        switch (type) {
            case TYPE_MC:
                squishVanillaUncompressed(nbt, new DataOutputStream(stream));
                return;
            case TYPE_MC_GZIP:
                squishVanilla(nbt, stream);
                return;
            case TYPE_BC_1:
                squishBuildCraftV1Uncompressed(nbt, new DataOutputStream(stream));
                return;
            case TYPE_BC_1_GZIP:
                squishBuildCraftV1(nbt, stream);
                return;
            default:
                throw new IllegalArgumentException("Unknown type " + type);
        }
    }

    public static void squishVanilla(CompoundTag nbt, OutputStream to) throws IOException {
        to.write(NbtSquishConstants.BUILDCRAFT_MAGIC_1);
        to.write(NbtSquishConstants.BUILDCRAFT_MAGIC_2);
        to.write(TYPE_MC_GZIP);
        NbtIo.writeCompressed(nbt, to);
    }

    public static void squishVanillaUncompressed(CompoundTag nbt, DataOutput to) throws IOException {
        to.writeShort(NbtSquishConstants.BUILDCRAFT_MAGIC);
        to.write(TYPE_MC);
        NbtIo.write(nbt, to);
    }

    public static void squishBuildCraftV1(CompoundTag nbt, OutputStream to) throws IOException {
        to.write(NbtSquishConstants.BUILDCRAFT_MAGIC_1);
        to.write(NbtSquishConstants.BUILDCRAFT_MAGIC_2);
        to.write(TYPE_BC_1_GZIP);
        try (GZIPOutputStream gzip = new GZIPOutputStream(to, true)) {
            squishBuildCraftV1Direct(nbt, new DataOutputStream(gzip));
        }
    }

    public static void squishBuildCraftV1Uncompressed(CompoundTag nbt, DataOutput to) throws IOException {
        to.write(NbtSquishConstants.BUILDCRAFT_MAGIC_1);
        to.write(NbtSquishConstants.BUILDCRAFT_MAGIC_2);
        to.write(TYPE_BC_1);
        squishBuildCraftV1Direct(nbt, to);
    }

    public static CompoundTag expand(byte[] bytes) throws IOException {
        return expand(new ByteArrayInputStream(bytes));
    }

    public static CompoundTag expand(ByteBuf buf) throws IOException {
        return expand(new ByteBufInputStream(buf));
    }

    /**
     * Decodes the BuildCraft V1 squish format with limits suitable for untrusted network input.
     * This deliberately does not accept vanilla/legacy fallback formats: callers using a known
     * network protocol should not let the remote peer choose an arbitrary decoder.
     */
    public static CompoundTag expandBuildCraftV1Limited(byte[] bytes, long maxExpandedBytes, long maxDecodeBudget)
        throws IOException {
        if (maxExpandedBytes <= 0 || maxDecodeBudget <= 0) {
            throw new IllegalArgumentException("Decode limits must be positive");
        }
        ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
        int byte1 = raw.read();
        int byte2 = raw.read();
        int type = raw.read();
        if (byte1 != NbtSquishConstants.BUILDCRAFT_MAGIC_1 || byte2 != NbtSquishConstants.BUILDCRAFT_MAGIC_2) {
            throw new InvalidInputDataException("Electronic-library upload is not BuildCraft squished NBT");
        }

        InputStream decoded;
        if (type == TYPE_BC_1) {
            decoded = raw;
        } else if (type == TYPE_BC_1_GZIP) {
            decoded = new GZIPInputStream(raw);
        } else {
            throw new InvalidInputDataException("Electronic-library upload uses unsupported NBT type " + type);
        }

        try (InputStream limited = new LimitedInputStream(decoded, maxExpandedBytes);
             DataInputStream input = new DataInputStream(limited)) {
            return readBuildCraftV1Direct(input, maxDecodeBudget);
        }
    }

    public static CompoundTag expand(InputStream stream) throws IOException {
        if (!stream.markSupported()) {
            stream = new BufferedInputStream(stream);
        }
        stream.mark(5);
        int byte1 = stream.read();
        int byte2 = stream.read();

        if (byte1 == NbtSquishConstants.BUILDCRAFT_MAGIC_1 && byte2 == NbtSquishConstants.BUILDCRAFT_MAGIC_2) {
            // Defiantly a BC stream
            int type = stream.read();
            if (type == TYPE_MC) {
                return NbtIo.read(new DataInputStream(stream), NbtAccounter.unlimitedHeap());
            } else if (type == TYPE_MC_GZIP) {
                return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
            } else if (type == TYPE_BC_1) {
                return readBuildCraftV1Direct(new DataInputStream(stream));
            } else if (type == TYPE_BC_1_GZIP) {
                return readBuildCraftV1Direct(new DataInputStream(new GZIPInputStream(stream)));
            } else {
            	stream.close();
                throw new InvalidInputDataException("Cannot handle BuildCraft saved NBT type " + type);
            }
        } else if (byte1 == NbtSquishConstants.GZIP_MAGIC_1 && byte2 == NbtSquishConstants.GZIP_MAGIC_2) {
            // Defiantly a GZIP stream
            // Assume its a vanilla file
            stream.reset();
            return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        }
        // Fall back to the legacy BuildCraft NBT encoding.
        // Reset + mark the same point, this time we only want to reset back 1 or 2 bytes
        stream.reset();
        stream.mark(5);
        int type = stream.read();

        if (type == TYPE_MC) {
            return NbtIo.read(new DataInputStream(stream), NbtAccounter.unlimitedHeap());
        } else if (type == TYPE_MC_GZIP) {
            return NbtIo.readCompressed(stream, NbtAccounter.unlimitedHeap());
        } else if (type == TYPE_BC_1) {
            return readBuildCraftV1Direct(new DataInputStream(stream));
        } else if (type == TYPE_BC_1_GZIP) {
            return readBuildCraftV1Direct(new DataInputStream(new GZIPInputStream(stream)));
        } else if (type == Tag.TAG_COMPOUND) {
            // Assume vanilla, but reset back to the first byte as vanilla needs
            stream.reset();
            return NbtIo.read(new DataInputStream(stream), NbtAccounter.unlimitedHeap());
        } else {
        	stream.close();
            throw new InvalidInputDataException("Cannot handle unknown saved NBT type " + type);
        }
        
    }

    private static CompoundTag readBuildCraftV1Direct(DataInput in) throws IOException {
        NbtSquishMap map = NbtSquishMapReader.read(in);
        WrittenType type = map.getWrittenType();
        int index = type.readIndex(in);
        return map.getFullyReadComp(index);
    }

    private static CompoundTag readBuildCraftV1Direct(DataInput in, long maxDecodeBudget) throws IOException {
        NbtSquishMap map = NbtSquishMapReader.read(in, maxDecodeBudget);
        WrittenType type = map.getWrittenType();
        int index = type.readIndex(in);
        return map.getFullyReadComp(index);
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            remaining = limit;
        }

        @Override
        public int read() throws IOException {
            ensureRemaining();
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            ensureRemaining();
            int allowed = (int) Math.min((long) len, remaining);
            int read = super.read(bytes, off, allowed);
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            ensureRemaining();
            long skipped = super.skip(Math.min(count, remaining));
            remaining -= skipped;
            return skipped;
        }

        private void ensureRemaining() throws InvalidInputDataException {
            if (remaining <= 0) {
                throw new InvalidInputDataException("Expanded BuildCraft NBT exceeds network decode limit");
            }
        }
    }

    private static void squishBuildCraftV1Direct(CompoundTag nbt, DataOutput to) throws IOException {
        NbtSquishMap map = new NbtSquishMap();
        map.addTag(nbt);
        NbtSquishMapWriter.debug = debugBuffer != null;
        NbtSquishMapWriter.write(map, to);
        WrittenType type = map.getWrittenType();
        type.writeIndex(to, map.indexOfTag(nbt));
    }
}
