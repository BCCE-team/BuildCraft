/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import javax.annotation.Nullable;

import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.lib.internal.enums.EnumSnapshotType;
import buildcraft.lib.misc.HashUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.RotationUtil;
import buildcraft.lib.misc.StringUtilBC;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.net.NetworkSecurity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;

public abstract class Snapshot {
    public Key key = new Key();
    public BlockPos size;
    public Direction facing;
    public BlockPos offset;

    public static Snapshot create(EnumSnapshotType type) {
        switch (type) {
            case TEMPLATE:
                return new Template();
            case BLUEPRINT:
                return new Blueprint();
        }
        throw new UnsupportedOperationException();
    }

    
    public static int posToIndex(int sizeX, int sizeY, int sizeZ, int x, int y, int z) {
        return ((z * sizeY) + y) * sizeX + x;
    }

    public static int posToIndex(BlockPos size, int x, int y, int z) {
        return posToIndex(size.getX(), size.getY(), size.getZ(), x, y, z);
    }

    
    public static int posToIndex(int sizeX, int sizeY, int sizeZ, BlockPos pos) {
        return posToIndex(sizeX, sizeY, sizeZ, pos.getX(), pos.getY(), pos.getZ());
    }
    
    public static int posToIndex(BlockPos size, BlockPos pos) {
        return posToIndex(size.getX(), size.getY(), size.getZ(), pos.getX(), pos.getY(), pos.getZ());
    }

    public int posToIndex(int x, int y, int z) {
        return posToIndex(size, x, y, z);
    }
    
    public int posToIndex(BlockPos pos) {
        return posToIndex(size, pos);
    }

    public static BlockPos indexToPos(int sizeX, int sizeY, int sizeZ, int i) {
        return new BlockPos(
            i % sizeX,
            (i / sizeX) % sizeY,
            i / (sizeY * sizeX)
        );
    }

    public static BlockPos indexToPos(BlockPos size, int i) {
        return indexToPos(size.getX(), size.getY(), size.getZ(), i);
    }

    public BlockPos indexToPos(int i) {
        return indexToPos(size, i);
    }

    public static int getDataSize(int x, int y, int z) {
        return x * y * z;
    }

    public static int getDataSize(BlockPos size) {
        return getDataSize(size.getX(), size.getY(), size.getZ());
    }

    public int getDataSize() {
        return getDataSize(size);
    }

    public static CompoundTag writeToNBT(Snapshot snapshot) {
        CompoundTag nbt = snapshot.serializeNBT();
        nbt.put("type", NBTUtilBC.writeEnum(snapshot.getType()));
        return nbt;
    }

    public static Snapshot readFromNBT(CompoundTag nbt) throws InvalidInputDataException {
        Tag tag = nbt.get("type");
        EnumSnapshotType type = NBTUtilBC.readEnum(tag, EnumSnapshotType.class);
        if (type == null) {
            throw new InvalidInputDataException("Unknown snapshot type " + tag);
        }
        Snapshot snapshot = Snapshot.create(type);
        snapshot.deserializeNBT(nbt);
        return snapshot;
    }

    public CompoundTag serializeNBT() {
        CompoundTag nbt = new CompoundTag();
        nbt.put("key", key.serializeNBT());
        nbt.put("size", NbtUtils.writeBlockPos(size));
        nbt.put("facing", NBTUtilBC.writeEnum(facing));
        nbt.put("offset", NbtUtils.writeBlockPos(offset));
        return nbt;
    }

    public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
        key = new Key(nbt.getCompound("key"));
        size = NbtUtils.readBlockPos(nbt.getCompound("size"));
        facing = NBTUtilBC.readEnum(nbt.get("facing"), Direction.class);
        offset = NbtUtils.readBlockPos(nbt.getCompound("offset"));
    }

    abstract public Snapshot copy();

    abstract public EnumSnapshotType getType();

    public void computeKey() {
        CompoundTag nbt = writeToNBT(this);
        if (nbt.contains("key", Tag.TAG_COMPOUND)) {
            nbt.remove("key");
        }
        key = new Key(key, HashUtil.computeHash(nbt));
    }

    @Override
    public String toString() {
        return "Snapshot{" +
            "key=" + key +
            ", size=" + StringUtilBC.blockPosAsSizeToString(size) +
            ", facing=" + facing +
            ", offset=" + offset +
            "}";
    }

    public static class Key {
        public final byte[] hash;
        @Nullable // for client storage
        public final Header header;

        public Key() {
            this.hash = new byte[0];
            this.header = null;
        }

        public Key(Key oldKey, byte[] hash) {
            this.hash = hash;
            this.header = oldKey.header;
        }

        public Key(Key oldKey, @Nullable Header header) {
            this.hash = oldKey.hash;
            this.header = header;
        }

        public Key(CompoundTag nbt) {
            this(nbt, true);
        }

        private Key(CompoundTag nbt, boolean allowHeader) {
            hash = nbt.getByteArray("hash");
            boolean hasHeader = nbt.contains("header");
            if (hasHeader && !allowHeader) {
                throw new IllegalArgumentException("Nested snapshot key headers are not valid");
            }
            header = hasHeader ? new Header(nbt.getCompound("header")) : null;
        }

        public Key(FriendlyByteBuf buffer) {
            this(buffer, true);
        }

        private Key(FriendlyByteBuf buffer, boolean allowHeader) {
            int hashLength = NetworkSecurity.requireRange(
                buffer.readVarInt(), HashUtil.DIGEST_LENGTH, HashUtil.DIGEST_LENGTH, "snapshot key hash length"
            );
            NetworkSecurity.requireReadable(buffer, hashLength, "snapshot key hash");
            hash = new byte[hashLength];
            buffer.readBytes(hash);
            boolean hasHeader = buffer.readBoolean();
            if (hasHeader && !allowHeader) {
                throw new io.netty.handler.codec.DecoderException("Nested snapshot key headers are not valid");
            }
            header = hasHeader ? new Header(buffer) : null;
        }

        public CompoundTag serializeNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.putByteArray("hash", hash);
            if (header != null) {
                nbt.put("header", header.serializeNBT());
            }
            return nbt;
        }

        public void writeToByteBuf(FriendlyByteBuf buffer) {
            writeToByteBuf(buffer, true);
        }

        private void writeToByteBuf(FriendlyByteBuf buffer, boolean allowHeader) {
            if (hash.length != HashUtil.DIGEST_LENGTH) {
                throw new IllegalStateException("Cannot send an uncomputed snapshot key (hash length " + hash.length + ")");
            }
            if (header != null && !allowHeader) {
                throw new IllegalStateException("Nested snapshot key headers are not valid");
            }
            buffer.writeByteArray(hash);
            buffer.writeBoolean(header != null);
            if (header != null) {
                header.writeToByteBuf(buffer);
            }
        }

        @Override
        public boolean equals(Object o) {
            return this == o ||
                o != null &&
                    getClass() == o.getClass() &&
                    Arrays.equals(hash, ((Key) o).hash) &&
                    (header != null ? header.equals(((Key) o).header) : ((Key) o).header == null);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(hash);
        }

        @Override
        public String toString() {
            return HashUtil.convertHashToString(hash);
        }
    }

    public static class Header {
        private static final int MAX_OWNER_NAME_LENGTH = 64;
        private static final int MAX_BLUEPRINT_NAME_LENGTH = 256;

        public final Key key;
        public final UUID owner;
        public final String ownerName;
        public final Date created;
        public final String name;
        /** If true, a creative inserter may make the builder work without consuming materials. */
        public final boolean allowCreative;
        public final boolean canRotate;
        public final boolean canExcavate;

        public Header(Key key, UUID owner, Date created, String name) {
            this(key, owner, "", created, name, true, true, true);
        }

        public Header(Key key, UUID owner, String ownerName, Date created, String name) {
            this(key, owner, ownerName, created, name, true, true, true);
        }

        public Header(Key key, UUID owner, Date created, String name, boolean allowCreative, boolean canRotate,
            boolean canExcavate) {
            this(key, owner, "", created, name, allowCreative, canRotate, canExcavate);
        }

        public Header(Key key, UUID owner, String ownerName, Date created, String name, boolean allowCreative,
            boolean canRotate, boolean canExcavate) {
            this.key = key;
            this.owner = owner;
            this.ownerName = ownerName == null ? "" : ownerName;
            this.created = created;
            this.name = name;
            this.allowCreative = allowCreative;
            this.canRotate = canRotate;
            this.canExcavate = canExcavate;
        }

        public Header(CompoundTag nbt) {
            key = new Key(nbt.getCompound("key"), false);
            owner = nbt.getUUID("owner");
            ownerName = nbt.contains("ownerName", Tag.TAG_STRING) ? nbt.getString("ownerName") : "";
            created = new Date(nbt.getLong("created"));
            name = nbt.getString("name");
            // Blueprints/templates without these switches use permissive defaults: creative insertion may skip
            // materials, rotation is allowed, and excavation is allowed.
            allowCreative = !nbt.contains("allowCreative") || nbt.getBoolean("allowCreative");
            canRotate = !nbt.contains("canRotate") || nbt.getBoolean("canRotate");
            canExcavate = !nbt.contains("canExcavate") || nbt.getBoolean("canExcavate");
        }

        public Header(FriendlyByteBuf buffer) {
            key = new Key(buffer, false);
            owner = buffer.readUUID();
            ownerName = buffer.readUtf(MAX_OWNER_NAME_LENGTH);
            created = new Date(buffer.readLong());
            name = buffer.readUtf(MAX_BLUEPRINT_NAME_LENGTH);
            allowCreative = buffer.readBoolean();
            canRotate = buffer.readBoolean();
            canExcavate = buffer.readBoolean();
        }

        public CompoundTag serializeNBT() {
            CompoundTag nbt = new CompoundTag();
            nbt.put("key", key.serializeNBT());
            nbt.putUUID("owner", owner);
            nbt.putString("ownerName", ownerName);
            nbt.putLong("created", created.getTime());
            nbt.putString("name", name);
            nbt.putBoolean("allowCreative", allowCreative);
            nbt.putBoolean("canRotate", canRotate);
            nbt.putBoolean("canExcavate", canExcavate);
            return nbt;
        }

        public void writeToByteBuf(FriendlyByteBuf buffer) {
            key.writeToByteBuf(buffer, false);
            buffer.writeUUID(owner);
            buffer.writeUtf(ownerName, MAX_OWNER_NAME_LENGTH);
            buffer.writeLong(created.getTime());
            buffer.writeUtf(name, MAX_BLUEPRINT_NAME_LENGTH);
            buffer.writeBoolean(allowCreative);
            buffer.writeBoolean(canRotate);
            buffer.writeBoolean(canExcavate);
        }

        public Player getOwnerPlayer(Level world) {
            return world.getPlayerByUUID(owner);
        }

        @Override
        public boolean equals(Object o) {
            return this == o ||
                o != null &&
                    getClass() == o.getClass() &&
                    key.equals(((Header) o).key) &&
                    owner.equals(((Header) o).owner) &&
                    ownerName.equals(((Header) o).ownerName) &&
                    created.equals(((Header) o).created) &&
                    name.equals(((Header) o).name) &&
                    allowCreative == ((Header) o).allowCreative &&
                    canRotate == ((Header) o).canRotate &&
                    canExcavate == ((Header) o).canExcavate;
        }

        @Override
        public int hashCode() {
            int result = key.hashCode();
            result = 31 * result + owner.hashCode();
            result = 31 * result + ownerName.hashCode();
            result = 31 * result + created.hashCode();
            result = 31 * result + name.hashCode();
            result = 31 * result + Boolean.hashCode(allowCreative);
            result = 31 * result + Boolean.hashCode(canRotate);
            result = 31 * result + Boolean.hashCode(canExcavate);
            return result;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public abstract class BuildingInfo {
        public final BlockPos basePos;
        public final BlockPos offsetPos;
        public final Rotation rotation;
        public final Box box = new Box();

        protected BuildingInfo(BlockPos basePos, Rotation rotation) {
            this.basePos = basePos;
            this.offsetPos = basePos.offset(offset.rotate(rotation));
            this.rotation = rotation;
            this.box.extendToEncompass(toWorld(BlockPos.ZERO));
            this.box.extendToEncompass(toWorld(size.subtract(VecUtil.POS_ONE)));
        }

        public BlockPos toWorld(BlockPos blockPos) {
            return blockPos
                .rotate(rotation)
                .offset(offsetPos);
        }

        public BlockPos fromWorld(BlockPos blockPos) {
            return blockPos
                .subtract(offsetPos)
                .rotate(RotationUtil.invert(rotation));
        }

        public abstract Snapshot getSnapshot();
    }
}
