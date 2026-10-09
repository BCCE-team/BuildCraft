//? source if >=1.21.1
package buildcraft.lib.compat.minecraft.persistence;

import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Internal writer; preserves the existing NBT layout and registry context. */
public final class BCValueOutput {
    private final CompoundTag tag;
    @Nullable private final HolderLookup.Provider registries;

    public BCValueOutput(CompoundTag tag, @Nullable HolderLookup.Provider registries) {
        this.tag = Objects.requireNonNull(tag, "tag");
        this.registries = registries;
    }

    public CompoundTag tag() { return tag; }
    public boolean hasRegistries() { return registries != null; }
    public HolderLookup.Provider registries() {
        return Objects.requireNonNull(registries, "Cannot serialize registry-dependent data without registries");
    }
    public boolean isEmpty() { return tag.isEmpty(); }
    public void put(String key, Tag value) { tag.put(key, value); }
    public void writeBoolean(String key, boolean value) { tag.putBoolean(key, value); }
    public void writeByte(String key, byte value) { tag.putByte(key, value); }
    public void writeShort(String key, short value) { tag.putShort(key, value); }
    public void writeInt(String key, int value) { tag.putInt(key, value); }
    public void writeLong(String key, long value) { tag.putLong(key, value); }
    public void writeFloat(String key, float value) { tag.putFloat(key, value); }
    public void writeDouble(String key, double value) { tag.putDouble(key, value); }
    public void writeString(String key, String value) { tag.putString(key, value); }
    public void writeByteArray(String key, byte[] value) { tag.putByteArray(key, value); }
    public void writeIntArray(String key, int[] value) { tag.putIntArray(key, value); }
    public void writeLongArray(String key, long[] value) { tag.putLongArray(key, value); }
    public void writeUUID(String key, UUID value) { TagAccess.putUUID(tag, key, value); }
}
