/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.chunk;

import buildcraft.lib.BCLib;
import buildcraft.lib.chunkload.IChunkLoadingTile;
import buildcraft.lib.compat.NbtCompat;
import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Fabric lacks NeoForge's owner-tagged persistent ticket controller. This
 * adapter stores a per-dimension owner -> chunk ledger in SavedData, and uses
 * vanilla's persistent, ticking forced-chunk mechanism to preserve the Quarry
 * work area. It never removes a pre-existing external force from a chunk.
 *
 * The "ticking" false category is intentionally not persisted: the shared
 * BCChunkTickets coordinator removes that compatibility ticket immediately
 * before installing the real ticking one. Only true ticking entries are owned.
 */
public final class PlatformChunkTickets {
    private static boolean registered;
    private static final Map<ServerLevel, Integer> ORPHAN_SWEEP_TICKS = new java.util.WeakHashMap<>();

    private PlatformChunkTickets() {}

    public static synchronized void init() {
        if (registered) return;
        registered = true;
        ServerLevelEvents.LOAD.register((server, level) ->
            BCChunkTickets.validateTickets(level, new BCTicketOwners() {
                public Set<BlockPos> owners() {
                    return data(level).owners.keySet().stream()
                        .map(BlockPos::of)
                        .collect(Collectors.toCollection(HashSet::new));
                }
                public void removeAllTickets(BlockPos owner) {
                    releaseOwner(level, owner);
                }
            }));
        ServerLevelEvents.UNLOAD.register((server, level) -> ORPHAN_SWEEP_TICKS.remove(level));
        // Vanilla persisted force flags can outlive a Quarry which was removed
        // while its owner chunk was not ticking. Check only loaded owner chunks,
        // periodically and on the server thread; never generate/load a new
        // chunk just to verify its ownership.
        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            int ticks = ORPHAN_SWEEP_TICKS.getOrDefault(level, 0) + 1;
            ORPHAN_SWEEP_TICKS.put(level, ticks % 100);
            if (ticks < 100) return;
            TicketData ledger = data(level);
            for (Long packed : new HashSet<>(ledger.owners.keySet())) {
                BlockPos pos = BlockPos.of(packed);
                if (!level.hasChunkAt(pos)) continue;
                var tile = level.getBlockEntity(pos);
                if (!(tile instanceof IChunkLoadingTile) || tile.isRemoved()) {
                    releaseOwner(level, pos);
                }
            }
        });
    }

    public static Set<ChunkPos> getTrackedChunks(ServerLevel level, BlockPos owner) {
        Set<Long> values = data(level).owners.get(owner.asLong());
        if (values == null || values.isEmpty()) return Collections.emptySet();
        return values.stream().map(ChunkPos::unpack).collect(Collectors.toCollection(HashSet::new));
    }

    public static boolean forceChunk(ServerLevel level, BlockPos owner, ChunkPos chunk,
                                     boolean add, boolean ticking) {
        if (!ticking) return true;
        TicketData data = data(level);
        long key = chunk.pack();
        long ownerKey = owner.asLong();

        if (add) {
            Set<Long> refs = data.owners.computeIfAbsent(ownerKey, unused -> new HashSet<>());
            if (!refs.add(key)) return true;
            // setChunkForced returns whether the forced status changed. Only
            // BCCE-created forces are eligible for automatic release.
            boolean created = level.setChunkForced(chunk.x(), chunk.z(), true);
            if (created) data.owned.add(key);
            data.setDirty();
            return true;
        }

        Set<Long> refs = data.owners.get(ownerKey);
        if (refs == null || !refs.remove(key)) return true;
        if (refs.isEmpty()) data.owners.remove(ownerKey);
        if (!data.isReferenced(key) && data.owned.remove(key)) {
            level.setChunkForced(chunk.x(), chunk.z(), false);
        }
        data.setDirty();
        return true;
    }

    public static void releaseOwner(ServerLevel level, BlockPos owner) {
        TicketData data = data(level);
        Set<Long> chunks = data.owners.get(owner.asLong());
        if (chunks == null || chunks.isEmpty()) return;
        for (long chunk : new HashSet<>(chunks)) {
            forceChunk(level, owner, ChunkPos.unpack(chunk), false, true);
        }
    }

    private static TicketData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TicketData.TYPE);
    }

    private static final class TicketData extends SavedData {
        private static final Codec<TicketData> CODEC =
            CompoundTag.CODEC.xmap(TicketData::new, TicketData::save);
        private static final SavedDataType<TicketData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(BCLib.MODID, "machine_chunk_owners"),
            TicketData::new, CODEC, null
        );
        private final Map<Long, Set<Long>> owners = new HashMap<>();
        private final Set<Long> owned = new HashSet<>();

        private TicketData() {}

        private TicketData(CompoundTag tag) {
            for (long packed : NbtCompat.getLongArray(tag, "createdForces")) {
                owned.add(packed);
            }
            ListTag entries = NbtCompat.getList(tag, "owners");
            for (int index = 0; index < entries.size(); index++) {
                CompoundTag entry = NbtCompat.getCompound(entries, index);
                long owner = NbtCompat.getLong(entry, "pos");
                Set<Long> chunks = new HashSet<>();
                for (long packed : NbtCompat.getLongArray(entry, "chunks")) chunks.add(packed);
                if (!chunks.isEmpty()) owners.put(owner, chunks);
            }
            // A malformed or outdated saved-data file must never authorize
            // unforcing somebody else's persistent vanilla forced chunk.
            owned.removeIf(chunk -> !isReferenced(chunk));
        }

        private boolean isReferenced(long chunk) {
            for (Set<Long> references : owners.values()) {
                if (references.contains(chunk)) return true;
            }
            return false;
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLongArray("createdForces", owned.stream().mapToLong(Long::longValue).toArray());
            ListTag entries = new ListTag();
            owners.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    CompoundTag row = new CompoundTag();
                    row.putLong("pos", entry.getKey());
                    row.putLongArray("chunks", entry.getValue().stream()
                        .mapToLong(Long::longValue).sorted().toArray());
                    entries.add(row);
                });
            tag.put("owners", entries);
            return tag;
        }
    }
}
