/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.nbt;

import java.io.DataInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.data.NbtSquishConstants;
import buildcraft.lib.misc.data.DecompactingBitSet;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

class NbtSquishMapReader {
    private static final long MAX_EXPANDED_BUDGET = 4L * 1024 * 1024;
    private static final int MAX_NBT_DEPTH = 64;
    private final NbtSquishMap map = new NbtSquishMap();
    private long remainingBudget;

    private NbtSquishMapReader(long maxBudget) {
        if (maxBudget < 0) {
            throw new IllegalArgumentException("maxBudget must be non-negative");
        }
        // The unbounded overload must not make attacker-controlled compressed input unlimited.
        remainingBudget = Math.min(maxBudget, MAX_EXPANDED_BUDGET);
    }

    public static NbtSquishMap read(DataInput in) throws IOException {
        return new NbtSquishMapReader(Long.MAX_VALUE).readInternal(in);
    }

    public static NbtSquishMap read(DataInput in, long maxBudget) throws IOException {
        return new NbtSquishMapReader(maxBudget).readInternal(in);
    }

    private NbtSquishMap readInternal(DataInput in) throws IOException {
        WrittenType type = WrittenType.readType(in);
        int flags = in.readInt();

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_BYTES)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                map.bytes.add(in.readByte());
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_SHORTS)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                map.shorts.add(in.readShort());
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_INTS)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                map.ints.add(in.readInt());
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_LONGS)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                map.longs.add(in.readLong());
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_FLOATS)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                map.floats.add(in.readFloat());
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_DOUBLES)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                map.doubles.add(in.readDouble());
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_BYTE_ARRAYS)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                int arraySize = in.readUnsignedShort();
                consumeBudget(arraySize, "byte array contents");
                ByteArrayList list = new ByteArrayList(arraySize);
                for (int j = 0; j < arraySize; j++) {
                    list.add(in.readByte());
                }
                map.byteArrays.add(list);
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_INT_ARRAYS)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                int arraySize = in.readUnsignedShort();
                consumeBudget(arraySize, "int array contents");
                IntArrayList list = new IntArrayList(arraySize);
                for (int j = 0; j < arraySize; j++) {
                    list.add(in.readInt());
                }
                map.intArrays.add(list);
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_STRINGS)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                int length = in.readUnsignedShort();
                consumeBudget(length, "string bytes");
                byte[] bytes = new byte[length];
                in.readFully(bytes);
                map.strings.add(new String(bytes, StandardCharsets.UTF_8));
            }
        }

        if (isFlag(flags, NbtSquishConstants.FLAG_HAS_COMPLEX)) {
            int count = readCount(in, "entry count");
            for (int i = 0; i < count; i++) {
                int complexType = in.readUnsignedByte();
                if (complexType == NbtSquishConstants.COMPLEX_COMPOUND) {
                    map.complex.add(readCompound(type, in));
                } else if (complexType == NbtSquishConstants.COMPLEX_LIST) {
                    map.complex.add(readNormalList(type, in));
                } else if (complexType == NbtSquishConstants.COMPLEX_LIST_PACKED) {
                    map.complex.add(readPackedList(type, in));
                } else {
                    throw new IOException("Unknown complex type " + complexType);
                }
            }
        }

        return map;
    }

    private int readCount(DataInput in, String what) throws IOException {
        int count = readVarInt(in);
        if (count < 0) {
            throw new InvalidInputDataException("Negative " + what + ": " + count);
        }
        consumeBudget(count, what);
        return count;
    }

    private void consumeBudget(long amount, String what) throws InvalidInputDataException {
        if (amount < 0 || amount > remainingBudget) {
            throw new InvalidInputDataException(
                "NBT squish " + what + " exceeds decode budget: " + amount + " > " + remainingBudget
            );
        }
        remainingBudget -= amount;
    }

    /** Similar to {@link FriendlyByteBuf#readVarInt()} */
    private static int readVarInt(DataInput in) throws IOException {
        int value = 0;
        int bytesRead = 0;

        while (true) {
            int b = in.readUnsignedByte();
            value |= (b & 0x7f) << bytesRead * 7;
            bytesRead++;
            if (bytesRead > 5) {
                throw new InvalidInputDataException("VarInt can only be up to 5 bytes long!");
            }
            if ((b & 0x80) == 0) {
                // End of number
                return value;
            }
        }
    }

    private static boolean isFlag(int flags, int flag) {
        return (flags & flag) == flag;
    }

    private CompoundTag readCompound(WrittenType type, DataInput in) throws IOException {
        WrittenType stringType = WrittenType.getForSize(map.stringSize());
        int count = readCount(in, "entry count");
        CompoundTag nbt = new CompoundTag();
        for (int i = 0; i < count; i++) {
            String key = map.getStringForReading(stringType.readIndex(in));
            Tag value = map.getTagForReading(type.readIndex(in));
            // Compound dictionary entries can refer to previously expanded compounds.
            // Charge for every descendant BEFORE copying; dictionary-only accounting
            // misses exponential copy amplification with tiny input streams.
            chargeCopy(value, 0);
            nbt.put(key, value.copy());
        }
        return nbt;
    }

    private void chargeCopy(Tag tag, int depth) throws InvalidInputDataException {
        if (depth > MAX_NBT_DEPTH) {
            throw new InvalidInputDataException("NBT squish nesting exceeds " + MAX_NBT_DEPTH);
        }
        consumeBudget(1, "expanded tag");
        if (tag instanceof CompoundTag compound) {
            // CompoundTag#getAllKeys was renamed to keySet in modern Minecraft.
            //? if >=1.21.11 {
            for (String key : compound.keySet()) {
            //? } else {
            for (String key : compound.getAllKeys()) {
            //? }
                Tag child = compound.get(key);
                if (child != null) chargeCopy(child, depth + 1);
            }
        } else if (tag instanceof ListTag list) {
            for (int i = 0; i < list.size(); i++) {
                chargeCopy(list.get(i), depth + 1);
            }
        } else if (tag instanceof net.minecraft.nbt.ByteArrayTag array) {
            consumeBudget(array.getAsByteArray().length, "expanded byte array");
        } else if (tag instanceof net.minecraft.nbt.IntArrayTag array) {
            consumeBudget(4L * array.getAsIntArray().length, "expanded int array");
        } else if (tag instanceof net.minecraft.nbt.LongArrayTag array) {
            consumeBudget(8L * array.getAsLongArray().length, "expanded long array");
        }
    }

    private ListTag readNormalList(WrittenType type, DataInput in) throws IOException {
        int count = readCount(in, "entry count");
        ListTag list = new ListTag();

        for (int i = 0; i < count; i++) {
            int index = type.readIndex(in);
            list.add(map.getTagForReading(index));
        }

        return list;
    }

    private ListTag readPackedList(WrittenType type, DataInput in) throws IOException {
        // First make the dictionary
        int count = readCount(in, "entry count");
        List<Tag> dictionary = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int index = type.readIndex(in);
            Tag nbt = map.getTagForReading(index);
            dictionary.add(nbt);
        }
        List<Tag> list = new ArrayList<>();
        IntArrayList left = new IntArrayList();
        int bits = 1;
        int entries = readCount(in, "packed list entries");
        if (entries > 0 && dictionary.isEmpty()) {
            throw new InvalidInputDataException("Packed list has entries but no dictionary");
        }

        for (int i = 0; i < entries; i++) {
            list.add(null);
            left.add(i);
        }

        while (!dictionary.isEmpty()) {
            int bitsetSize = readCount(in, "packed list bitset bytes");
            byte[] bitsetData = new byte[bitsetSize];
            in.readFully(bitsetData);
            DecompactingBitSet decompactor = new DecompactingBitSet(bits, bitsetData);

            IntArrayList nextLeft = new IntArrayList();

            int maxVal = (1 << bits) - 1;
            for (int i : left) {
                int index = decompactor.next();
                if (index < maxVal) {
                    list.set(i, dictionary.get(index));
                } else {
                    nextLeft.add(i);
                }
            }

            dictionary.subList(0, Math.min(dictionary.size(), maxVal)).clear();
            left = nextLeft;
            bits++;
        }

        ListTag tag = new ListTag();
        for (Tag base : list) {
            tag.add(base);
        }
        return tag;
    }
}
