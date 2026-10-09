//? source if >=1.21.11
package buildcraft.lib.compat;

import java.util.Optional;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.Set;

import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/** Compatibility facade for CompoundTag access across modern targets. */
public final class NbtCompat {
    private NbtCompat() {}

    public static CompoundTag getCompound(CompoundTag tag, String key) {
        return tag.getCompound(key).orElseGet(CompoundTag::new);
    }

    /** Equivalent to the pre-1.21.11 CompoundTag#contains(String, int) overload. */
    public static boolean contains(CompoundTag tag, String key, int expectedType) {
        Tag value = tag.get(key);
        if (value == null) {
            return false;
        }
        // The numeric wildcard sentinel (TAG_ANY_NUMERIC) is 99.
        if (expectedType == 99) {
            return value instanceof NumericTag;
        }
        return value.getId() == expectedType;
    }

    public static boolean getBoolean(CompoundTag tag, String key) {
        return tag.getBoolean(key).orElse(false);
    }

    public static byte[] getByteArray(CompoundTag tag, String key) {
        return tag.getByteArray(key).orElse(new byte[0]);
    }

    public static int[] getIntArray(CompoundTag tag, String key) {
        return tag.getIntArray(key).orElse(new int[0]);
    }

    public static String getString(CompoundTag tag, String key) {
        return tag.getString(key).orElse("");
    }

    public static long getLong(CompoundTag tag, String key) {
        return tag.getLong(key).orElse(0L);
    }

    public static double getDouble(CompoundTag tag, String key) {
        return tag.getDouble(key).orElse(0.0D);
    }

    public static int getInt(CompoundTag tag, String key) {
        return tag.getInt(key).orElse(0);
    }

    public static byte getByte(CompoundTag tag, String key) {
        return tag.getByte(key).orElse((byte) 0);
    }

    public static float getFloat(CompoundTag tag, String key) {
        return tag.getFloat(key).orElse(0.0F);
    }


    public static ListTag getList(CompoundTag tag, String key) {
        return tag.getList(key).orElseGet(ListTag::new);
    }

    public static CompoundTag getCompound(ListTag tag, int index) {
        return tag.getCompound(index).orElseGet(CompoundTag::new);
    }

    public static int getInt(ListTag tag, int index) {
        if (index < 0 || index >= tag.size()) return 0;
        Tag value = tag.get(index);
        return value instanceof NumericTag ? getInt((NumericTag) value) : 0;
    }

    public static long getLong(ListTag tag, int index) {
        if (index < 0 || index >= tag.size()) return 0L;
        Tag value = tag.get(index);
        return value instanceof NumericTag ? getLong((NumericTag) value) : 0L;
    }

    public static double getDouble(ListTag tag, int index) {
        if (index < 0 || index >= tag.size()) return 0.0D;
        Tag value = tag.get(index);
        return value instanceof NumericTag ? getDouble((NumericTag) value) : 0.0D;
    }

    public static String getString(ListTag tag, int index) {
        if (index < 0 || index >= tag.size()) return "";
        return tag.getString(index).orElse("");
    }

    public static Set<String> getAllKeys(CompoundTag tag) {
        return tag.keySet();
    }

    public static Tag get(CompoundTag tag, String key) {
        return tag.get(key);
    }

    public static short getShort(CompoundTag tag, String key) {
        return tag.getShort(key).orElse((short) 0);
    }

    public static long[] getLongArray(CompoundTag tag, String key) {
        return tag.getLongArray(key).orElse(new long[0]);
    }

    public static String getString(StringTag tag) {
        return tag == null ? "" : tag.asString().orElse("");
    }

    public static String getString(Tag tag) {
        return tag instanceof StringTag stringTag ? getString(stringTag) : "";
    }

    public static CompoundTag parseTag(String raw) throws CommandSyntaxException {
        return TagParser.parseCompoundFully(raw);
    }

    public static String getString(JsonElement element) {
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    public static byte getByte(NumericTag tag) {
        return tag.asByte().orElse((byte) 0);
    }

    public static short getShort(NumericTag tag) {
        return tag.asShort().orElse((short) 0);
    }

    public static int getInt(NumericTag tag) {
        return tag.asInt().orElse(0);
    }

    public static long getLong(NumericTag tag) {
        return tag.asLong().orElse(0L);
    }

    public static float getFloat(NumericTag tag) {
        return tag.asFloat().orElse(0.0F);
    }

    public static double getDouble(NumericTag tag) {
        return tag.asDouble().orElse(0.0D);
    }

    public static int getInt(net.minecraft.nbt.IntTag tag) {
        return tag.asInt().orElse(0);
    }

    public static short getShort(net.minecraft.nbt.ShortTag tag) {
        return tag.asShort().orElse((short) 0);
    }

    public static long getLong(net.minecraft.nbt.LongTag tag) {
        return tag.asLong().orElse(0L);
    }

    public static float getFloat(net.minecraft.nbt.FloatTag tag) {
        return tag.asFloat().orElse(0.0F);
    }

    public static double getDouble(net.minecraft.nbt.DoubleTag tag) {
        return tag.asDouble().orElse(0.0D);
    }

    public static BlockPos readBlockPos(CompoundTag parent, String key) {
        return readBlockPos(parent.get(key));
    }

    public static boolean hasUUID(CompoundTag tag, String key) {
        return tag != null && loadUUID(tag.get(key)) != null;
    }

    public static UUID getUUID(CompoundTag tag, String key) {
        return loadUUID(tag.get(key));
    }

    public static void putUUID(CompoundTag tag, String key, UUID value) {
        tag.put(key, createUUID(value));
    }

    public static IntArrayTag createUUID(UUID uuid) {
        return new IntArrayTag(uuidToIntArray(uuid));
    }

    public static UUID loadUUID(Optional<? extends Tag> tag) {
        return tag.map(value -> loadUUID((Tag) value)).orElse(null);
    }

    public static UUID loadUUID(Tag tag) {
        if (tag instanceof IntArrayTag array) {
            int[] data = array.getAsIntArray();
            if (data.length >= 4) {
                long most = ((long) data[0] << 32) | (data[1] & 0xffffffffL);
                long least = ((long) data[2] << 32) | (data[3] & 0xffffffffL);
                return new UUID(most, least);
            }
        }
        if (tag instanceof CompoundTag compound) {
            int[] data = getIntArray(compound, "uuid");
            if (data.length < 4) data = getIntArray(compound, "UUID");
            if (data.length >= 4) {
                long most = ((long) data[0] << 32) | (data[1] & 0xffffffffL);
                long least = ((long) data[2] << 32) | (data[3] & 0xffffffffL);
                return new UUID(most, least);
            }
        }
        return null;
    }

    public static IntArrayTag writeBlockPos(BlockPos pos) {
        return new IntArrayTag(new int[] { pos.getX(), pos.getY(), pos.getZ() });
    }

    public static BlockPos readBlockPos(Optional<? extends Tag> tag) {
        return tag.map(value -> readBlockPos((Tag) value)).orElse(BlockPos.ZERO);
    }

    public static BlockPos readBlockPos(CompoundTag tag) {
        if (tag.contains("X") || tag.contains("x")) {
            String x = tag.contains("X") ? "X" : "x";
            String y = tag.contains("Y") ? "Y" : "y";
            String z = tag.contains("Z") ? "Z" : "z";
            return new BlockPos(getInt(tag, x), getInt(tag, y), getInt(tag, z));
        }
        return BlockPos.ZERO;
    }

    public static BlockPos readBlockPos(Tag tag) {
        if (tag instanceof IntArrayTag array) {
            int[] data = array.getAsIntArray();
            if (data.length >= 3) return new BlockPos(data[0], data[1], data[2]);
        }
        if (tag instanceof CompoundTag compound) return readBlockPos(compound);
        return BlockPos.ZERO;
    }

    private static int[] uuidToIntArray(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        return new int[] { (int) (most >> 32), (int) most, (int) (least >> 32), (int) least };
    }
}
