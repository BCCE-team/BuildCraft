package buildcraft.lib.compat.minecraft.persistence;

import net.minecraft.nbt.NbtOps;

import com.mojang.serialization.Codec;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import buildcraft.lib.compat.NbtCompat;

/** Internal, registry-aware reader. This is not part of the public addon API. */
public final class BCValueInput {
    private final CompoundTag tag;
    private final HolderLookup.Provider registries;

    public BCValueInput(CompoundTag tag, HolderLookup.Provider registries) {
        this.tag = Objects.requireNonNull(tag, "tag");
        this.registries = Objects.requireNonNull(registries, "registries");
    }

    /** Escape hatch for existing nested BCCE codecs; never a vanilla callback argument. */
    public CompoundTag tag() { return tag; }
    public HolderLookup.Provider registries() { return registries; }
    public boolean has(String key) { return tag.contains(key); }
    public boolean has(String key, int type) { return TagAccess.contains(tag, key, type); }
    public boolean readBoolean(String key) { return NbtCompat.getBoolean(TagAccess, tag, key); }
    public byte readByte(String key) { return NbtCompat.getByte(TagAccess, tag, key); }
    public short readShort(String key) { return TagAccess.getShort(tag, key); }
    public int readInt(String key) { return NbtCompat.getInt(TagAccess, tag, key); }
    public long readLong(String key) { return NbtCompat.getLong(TagAccess, tag, key); }
    public float readFloat(String key) { return NbtCompat.getFloat(TagAccess, tag, key); }
    public double readDouble(String key) { return NbtCompat.getDouble(TagAccess, tag, key); }
    public String readString(String key) { return NbtCompat.getString(TagAccess, tag, key); }
    public byte[] readByteArray(String key) { return NbtCompat.getByteArray(TagAccess, tag, key); }
    public int[] readIntArray(String key) { return NbtCompat.getIntArray(TagAccess, tag, key); }
    public long[] readLongArray(String key) { return TagAccess.getLongArray(tag, key); }
    public CompoundTag readCompound(String key) { return NbtCompat.getCompound(TagAccess, tag, key); }
    public ListTag readList(String key, int type) { return TagAccess.getList(tag, key, type); }
    public Tag get(String key) { return tag.get(key); }
    public UUID readUUID(String key) { return NbtCompat.getUUID(TagAccess, tag, key); }
    public boolean hasUUID(String key) { return NbtCompat.hasUUID(TagAccess, tag, key); }
    public Optional<CompoundTag> findCompound(String key) {
        return has(key, Tag.TAG_COMPOUND) ? Optional.of(readCompound(key)) : Optional.empty();
    }
    public <T> Optional<T> decode(String key, Codec<T> codec) {
        Tag value = tag.get(key);
        return value == null ? Optional.empty()
            : codec.parse(registries.createSerializationContext(NbtOps.INSTANCE), value).result();
    }
    public BCValueInput child(String key) { return new BCValueInput(readCompound(key), registries); }
}
