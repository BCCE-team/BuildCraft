package buildcraft.lib.compat.minecraft.persistence;

import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Internal legacy-family persistence writer that preserves existing NBT layouts. */
public final class BCValueOutput {
    private final CompoundTag tag;

    public BCValueOutput(CompoundTag tag) {
        this.tag = Objects.requireNonNull(tag, "tag");
    }

    public CompoundTag tag() {
        return tag;
    }

    public boolean isEmpty() {
        return tag.isEmpty();
    }

    public void put(String key, Tag value) {
        tag.put(key, value);
    }

    public void writeBoolean(String key, boolean value) {
        tag.putBoolean(key, value);
    }

    public void writeByte(String key, byte value) {
        tag.putByte(key, value);
    }

    public void writeShort(String key, short value) {
        tag.putShort(key, value);
    }

    public void writeInt(String key, int value) {
        tag.putInt(key, value);
    }

    public void writeLong(String key, long value) {
        tag.putLong(key, value);
    }

    public void writeFloat(String key, float value) {
        tag.putFloat(key, value);
    }

    public void writeDouble(String key, double value) {
        tag.putDouble(key, value);
    }

    public void writeString(String key, String value) {
        tag.putString(key, value);
    }

    public void writeByteArray(String key, byte[] value) {
        tag.putByteArray(key, value);
    }

    public void writeIntArray(String key, int[] value) {
        tag.putIntArray(key, value);
    }

    public void writeLongArray(String key, long[] value) {
        tag.putLongArray(key, value);
    }

    public void writeUUID(String key, UUID value) {
        tag.putUUID(key, value);
    }
}
