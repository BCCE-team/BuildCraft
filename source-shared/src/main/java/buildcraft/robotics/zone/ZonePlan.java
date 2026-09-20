/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics.zone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.logic.zone.ChunkGridMath;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.net.NetworkSecurity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class ZonePlan implements IZone {
    public static final int MAX_SERIALIZED_CHUNKS = 4096;

    private final HashMap<ChunkPos, ZoneChunk> chunkMapping = new HashMap<>();

    public ZonePlan() {}

    public ZonePlan(ZonePlan old) {
        for (ChunkPos chunkPos : old.chunkMapping.keySet()) {
            chunkMapping.put(chunkPos, new ZoneChunk(old.chunkMapping.get(chunkPos)));
        }
    }

    public boolean get(int x, int z) {
        int xChunk = ChunkGridMath.chunkCoordinate(x);
        int zChunk = ChunkGridMath.chunkCoordinate(z);
        ChunkPos chunkId = new ChunkPos(xChunk, zChunk);
        ZoneChunk property;

        if (!chunkMapping.containsKey(chunkId)) {
            return false;
        } else {
            property = chunkMapping.get(chunkId);
            return property.get(ChunkGridMath.localCoordinate(x), ChunkGridMath.localCoordinate(z));
        }
    }

    public void set(int x, int z, boolean val) {
        int xChunk = ChunkGridMath.chunkCoordinate(x);
        int zChunk = ChunkGridMath.chunkCoordinate(z);
        ChunkPos chunkId = new ChunkPos(xChunk, zChunk);
        ZoneChunk property;

        if (!chunkMapping.containsKey(chunkId)) {
            if (val) {
                property = new ZoneChunk();
                chunkMapping.put(chunkId, property);
            } else {
                return;
            }
        } else {
            property = chunkMapping.get(chunkId);
        }

        property.set(ChunkGridMath.localCoordinate(x), ChunkGridMath.localCoordinate(z), val);

        if (property.isEmpty()) {
            chunkMapping.remove(chunkId);
        }
    }

    public List<Vec2> getAll() {
        ImmutableList.Builder<Vec2> builder = ImmutableList.builder();
        chunkMapping.forEach((chunkPos, zoneChunk) -> {
            for (Vec2 p : zoneChunk.getAll()) {
                builder.add(new Vec2(
                    p.x + ChunkGridMath.minBlock(chunkPos.x),
                    p.y + ChunkGridMath.minBlock(chunkPos.z)
                ));
            }
        });
        return builder.build();
    }

    public ZonePlan copyChunksMatching(Predicate<ChunkPos> predicate) {
        ZonePlan filtered = new ZonePlan();
        chunkMapping.forEach((chunkPos, zoneChunk) -> {
            if (predicate.test(chunkPos)) {
                filtered.chunkMapping.put(chunkPos, new ZoneChunk(zoneChunk));
            }
        });
        return filtered;
    }

    public ZonePlan getWithOffset(int offsetX, int offsetY) {
        ZonePlan zonePlan = new ZonePlan();
        getAll().forEach(p -> zonePlan.set((int)p.x + offsetX, (int)p.y + offsetY, true));
        return zonePlan;
    }

    public boolean hasChunk(ChunkPos chunkPos) {
        return chunkMapping.containsKey(chunkPos);
    }

    public Set<ChunkPos> getChunkPoses() {
        return chunkMapping.keySet();
    }

    public HashMap<ChunkPos, ZoneChunk> getChunkMapping() {
        return chunkMapping;
    }

    public void writeToNBT(CompoundTag nbt) {
        nbt.put(
                "chunkMapping",
                NBTUtilBC.writeCompoundList(
                        chunkMapping.entrySet().stream()
                                .map(entry -> {
                                    CompoundTag zoneChunkTag = new CompoundTag();
                                    entry.getValue().writeToNBT(zoneChunkTag);
                                    zoneChunkTag.putInt("chunkX", entry.getKey().x);
                                    zoneChunkTag.putInt("chunkZ", entry.getKey().z);
                                    return zoneChunkTag;
                                })
                )
        );
    }

    public void readFromNBT(CompoundTag nbt) {
        chunkMapping.clear();
        NBTUtilBC.readCompoundList(nbt.get("chunkMapping"))
                .forEach(zoneChunkTag -> {
                    ZoneChunk chunk = new ZoneChunk();
                    chunk.readFromNBT(zoneChunkTag);
                    chunkMapping.put(
                            new ChunkPos(
                                    zoneChunkTag.getInt("chunkX"),
                                    zoneChunkTag.getInt("chunkZ")
                            ),
                            chunk
                    );
                });
    }

    @Override
    public double distanceTo(BlockPos index) {
        return Math.sqrt(distanceToSquared(index));
    }

    @Override
    public double distanceToSquared(BlockPos index) {
        double maxSqrDistance = Double.MAX_VALUE;

        for (Map.Entry<ChunkPos, ZoneChunk> e : chunkMapping.entrySet()) {
            double sqrDistance = ChunkGridMath.squaredDistanceToChunkCenter(
                index.getX(), index.getZ(), e.getKey().x, e.getKey().z
            );

            if (sqrDistance < maxSqrDistance) {
                maxSqrDistance = sqrDistance;
            }
        }

        return maxSqrDistance;
    }

    @Override
    public boolean contains(Vec3 point) {
        int xBlock = (int) Math.floor(point.x);
        int zBlock = (int) Math.floor(point.z);

        return get(xBlock, zBlock);
    }

    @Override
    public BlockPos getRandomBlockPos(Random rand) {
        if (chunkMapping.size() == 0) {
            return null;
        }

        int chunkId = rand.nextInt(chunkMapping.size());

        for (Map.Entry<ChunkPos, ZoneChunk> e : chunkMapping.entrySet()) {
            if (chunkId == 0) {
                BlockPos i = e.getValue().getRandomBlockPos(rand);
                int x = ChunkGridMath.minBlock(e.getKey().x) + i.getX();
                int z = ChunkGridMath.minBlock(e.getKey().z) + i.getZ();

                return new BlockPos(x, i.getY(), z);
            }

            chunkId--;
        }

        return null;
    }

    public ZonePlan readFromByteBuf(FriendlyByteBuf buf) {
        chunkMapping.clear();
        int size = NetworkSecurity.requireCount(buf.readInt(), MAX_SERIALIZED_CHUNKS, "zone-plan chunk count");
        for (int i = 0; i < size; i++) {
            ChunkPos key = new ChunkPos(buf.readInt(), buf.readInt());
            ZoneChunk value = new ZoneChunk();
            value.readFromByteBuf(buf);
            chunkMapping.put(key, value);
        }
        return this;
    }

    public void writeToByteBuf(FriendlyByteBuf buf) {
        if (chunkMapping.size() > MAX_SERIALIZED_CHUNKS) {
            throw new IllegalStateException("Zone plan is too large for a network packet: " + chunkMapping.size());
        }
        buf.writeInt(chunkMapping.size());
        for (Map.Entry<ChunkPos, ZoneChunk> e : chunkMapping.entrySet()) {
            buf.writeInt(e.getKey().x);
            buf.writeInt(e.getKey().z);
            e.getValue().writeToByteBuf(buf);
        }
    }
}
