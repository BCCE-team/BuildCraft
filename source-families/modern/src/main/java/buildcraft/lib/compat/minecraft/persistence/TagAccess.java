//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.persistence;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
//? if >=1.21.11 {
import buildcraft.lib.compat.NbtCompat;
//? }

/** Minecraft NBT accessor cliff. Keep version branches here, not in machine serializers. */
final class TagAccess {
    private TagAccess() {}
    static boolean contains(CompoundTag tag, String key, int expectedType) {
        Tag value = tag.get(key);
        return value != null && (expectedType == 99 ? value instanceof NumericTag : value.getId() == expectedType);
    }
//? if >=1.21.11 {
    static boolean getBoolean(CompoundTag tag, String key) { return NbtCompat.getBoolean(tag, key); }
    static byte getByte(CompoundTag tag, String key) { return NbtCompat.getByte(tag, key); }
    static short getShort(CompoundTag tag, String key) { return NbtCompat.getShort(tag, key); }
    static int getInt(CompoundTag tag, String key) { return NbtCompat.getInt(tag, key); }
    static long getLong(CompoundTag tag, String key) { return NbtCompat.getLong(tag, key); }
    static float getFloat(CompoundTag tag, String key) { return NbtCompat.getFloat(tag, key); }
    static double getDouble(CompoundTag tag, String key) { return NbtCompat.getDouble(tag, key); }
    static String getString(CompoundTag tag, String key) { return NbtCompat.getString(tag, key); }
    static byte[] getByteArray(CompoundTag tag, String key) { return NbtCompat.getByteArray(tag, key); }
    static int[] getIntArray(CompoundTag tag, String key) { return NbtCompat.getIntArray(tag, key); }
    static long[] getLongArray(CompoundTag tag, String key) { return NbtCompat.getLongArray(tag, key); }
    static CompoundTag getCompound(CompoundTag tag, String key) { return NbtCompat.getCompound(tag, key); }
    static ListTag getList(CompoundTag tag, String key, int type) {
        ListTag list = NbtCompat.getList(tag, key);
        return list.isEmpty() || list.get(0).getId() == type ? list : new ListTag();
    }
    static UUID getUUID(CompoundTag tag, String key) { return NbtCompat.getUUID(tag, key); }
    static boolean hasUUID(CompoundTag tag, String key) { return NbtCompat.hasUUID(tag, key); }
    static void putUUID(CompoundTag tag, String key, UUID value) { NbtCompat.putUUID(tag, key, value); }
//? } else {
    static boolean getBoolean(CompoundTag tag, String key) { return tag.getBoolean(key); }
    static byte getByte(CompoundTag tag, String key) { return tag.getByte(key); }
    static short getShort(CompoundTag tag, String key) { return tag.getShort(key); }
    static int getInt(CompoundTag tag, String key) { return tag.getInt(key); }
    static long getLong(CompoundTag tag, String key) { return tag.getLong(key); }
    static float getFloat(CompoundTag tag, String key) { return tag.getFloat(key); }
    static double getDouble(CompoundTag tag, String key) { return tag.getDouble(key); }
    static String getString(CompoundTag tag, String key) { return tag.getString(key); }
    static byte[] getByteArray(CompoundTag tag, String key) { return tag.getByteArray(key); }
    static int[] getIntArray(CompoundTag tag, String key) { return tag.getIntArray(key); }
    static long[] getLongArray(CompoundTag tag, String key) { return tag.getLongArray(key); }
    static CompoundTag getCompound(CompoundTag tag, String key) { return tag.getCompound(key); }
    static ListTag getList(CompoundTag tag, String key, int type) { return tag.getList(key, type); }
    static UUID getUUID(CompoundTag tag, String key) { return tag.getUUID(key); }
    static boolean hasUUID(CompoundTag tag, String key) { return tag.hasUUID(key); }
    static void putUUID(CompoundTag tag, String key, UUID value) { tag.putUUID(key, value); }
//? }
}
