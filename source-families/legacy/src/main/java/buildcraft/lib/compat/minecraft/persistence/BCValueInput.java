package buildcraft.lib.compat.minecraft.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * Internal legacy-family persistence reader.
 *
 * <p>Gameplay code should consume this boundary instead of binding new state logic directly to a vanilla
 * BlockEntity load signature. Existing CompoundTag layouts are preserved.</p>
 */
public final class BCValueInput {
    private final CompoundTag tag;

    public BCValueInput(CompoundTag tag) {
        this.tag = Objects.requireNonNull(tag, "tag");
    }

    public CompoundTag tag() {
        return tag;
    }

    public boolean has(String key) {
        return tag.contains(key);
    }

    public boolean has(String key, int type) {
        return tag.contains(key, type);
    }

    public boolean readBoolean(String key) {
        return tag.getBoolean(key);
    }

    public byte readByte(String key) {
        return tag.getByte(key);
    }

    public short readShort(String key) {
        return tag.getShort(key);
    }

    public int readInt(String key) {
        return tag.getInt(key);
    }

    public long readLong(String key) {
        return tag.getLong(key);
    }

    public float readFloat(String key) {
        return tag.getFloat(key);
    }

    public double readDouble(String key) {
        return tag.getDouble(key);
    }

    public String readString(String key) {
        return tag.getString(key);
    }

    public byte[] readByteArray(String key) {
        return tag.getByteArray(key);
    }

    public int[] readIntArray(String key) {
        return tag.getIntArray(key);
    }

    public long[] readLongArray(String key) {
        return tag.getLongArray(key);
    }

    public CompoundTag readCompound(String key) {
        return tag.getCompound(key);
    }

    public ListTag readList(String key, int type) {
        return tag.getList(key, type);
    }

    public Tag get(String key) {
        return tag.get(key);
    }

    public UUID readUUID(String key) {
        return tag.getUUID(key);
    }

    public boolean hasUUID(String key) {
        return tag.hasUUID(key);
    }

    public Optional<CompoundTag> findCompound(String key) {
        return has(key, Tag.TAG_COMPOUND) ? Optional.of(readCompound(key)) : Optional.empty();
    }

    /** Registry-independent codec helper for legacy persisted values. */
    public <T> Optional<T> decode(String key, Codec<T> codec) {
        Tag value = tag.get(key);
        return value == null ? Optional.empty() : codec.parse(NbtOps.INSTANCE, value).result();
    }

    public BCValueInput child(String key) {
        return new BCValueInput(readCompound(key));
    }
}
