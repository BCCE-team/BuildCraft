/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.builders.tile;

import buildcraft.builders.BCBuilders;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.misc.data.Box;
import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Removes a Quarry's temporary frame when its block entity is destroyed.
 *
 * <p>Only already-loaded chunks are edited immediately. Unloaded portions are
 * persisted, then reconciled on a future server tick after the chunks load.
 * Chunk unloading itself must never be mistaken for destroying a Quarry.
 */
public final class QuarryFrameCleanup {
    private static boolean registered;
    private static final int MAX_FRAME_POSITIONS = 16_384;
    private static final int MAX_DEFERRED_CHECKS_PER_TICK = 2_048;

    private QuarryFrameCleanup() {}

    public static synchronized void init() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_LEVEL_TICK.register(level -> {
            if (level.getGameTime() % 20 == 0) process(level);
        });
    }

    public static void clean(ServerLevel level, BlockPos owner, Box box, List<BlockPos> framePoses) {
        Set<BlockPos> positions = new HashSet<>();
        if (box.isInitialized()) {
            // Protect against unbounded / corrupted saved coordinates.
            long count = box.getBlocksOnEdgeCount();
            if (count > 0 && count <= MAX_FRAME_POSITIONS) {
                positions.addAll(box.getBlocksOnEdge());
            }
        }
        if (positions.isEmpty()) {
            // Fallback for worlds where only the runtime frame list survived.
            for (BlockPos framePos : framePoses) {
                if (positions.size() >= MAX_FRAME_POSITIONS) break;
                positions.add(framePos);
            }
        }
        if (positions.isEmpty()) return;

        Set<Long> deferred = new HashSet<>();
        for (BlockPos pos : positions) {
            if (!level.hasChunkAt(pos)) {
                deferred.add(pos.asLong());
            } else if (level.getBlockState(pos).is(BCBuildersBlocks.FRAME.get())) {
                removeFrame(level, pos);
            }
        }
        if (!deferred.isEmpty()) {
            Pending data = data(level);
            data.byOwner.computeIfAbsent(owner.asLong(), ignored -> new HashSet<>()).addAll(deferred);
            data.setDirty();
        }
    }

    private static void process(ServerLevel level) {
        Pending data = data(level);
        if (data.byOwner.isEmpty()) return;

        boolean changed = false;
        int checks = MAX_DEFERRED_CHECKS_PER_TICK;
        for (Map.Entry<Long, Set<Long>> entry : new HashMap<>(data.byOwner).entrySet()) {
            if (checks <= 0) break;
            BlockPos owner = BlockPos.of(entry.getKey());
            // A new Quarry at the same origin takes precedence over a stale cleanup request.
            if (level.hasChunkAt(owner) && level.getBlockEntity(owner) instanceof TileQuarry) {
                data.byOwner.remove(entry.getKey());
                changed = true;
                continue;
            }
            for (Long packed : new HashSet<>(entry.getValue())) {
                if (checks <= 0) break;
                BlockPos pos = BlockPos.of(packed);
                if (!level.hasChunkAt(pos)) continue;
                checks--;
                removeFrame(level, pos);
                entry.getValue().remove(packed);
                changed = true;
            }
            if (entry.getValue().isEmpty()) data.byOwner.remove(entry.getKey());
        }
        if (changed) data.setDirty();
    }

    private static void removeFrame(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!state.is(BCBuildersBlocks.FRAME.get())) return;
        // Frames placed in water retain their source water on teardown.
        level.setBlockAndUpdate(pos, state.getValue(BlockBCBase_Neptune.WATERLOGGED)
            ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState());
    }

    private static Pending data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Pending.TYPE);
    }

    private static final class Pending extends SavedData {
        private static final Codec<Pending> CODEC = CompoundTag.CODEC.xmap(Pending::new, Pending::save);
        private static final SavedDataType<Pending> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(BCBuilders.MODID, "quarry_frame_cleanup"), Pending::new, CODEC, null
        );

        private final Map<Long, Set<Long>> byOwner = new HashMap<>();

        private Pending() {}

        private Pending(CompoundTag tag) {
            ListTag list = NbtCompat.getList(tag, "pending");
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = NbtCompat.getCompound(list, i);
                Set<Long> positions = new HashSet<>();
                for (long packed : NbtCompat.getLongArray(entry, "positions")) {
                    if (positions.size() >= MAX_FRAME_POSITIONS) break;
                    positions.add(packed);
                }
                if (!positions.isEmpty()) {
                    byOwner.put(NbtCompat.getLong(entry, "owner"), positions);
                }
            }
        }

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            ListTag list = new ListTag();
            for (Map.Entry<Long, Set<Long>> entry : byOwner.entrySet()) {
                CompoundTag row = new CompoundTag();
                row.putLong("owner", entry.getKey());
                row.putLongArray("positions", entry.getValue().stream().mapToLong(Long::longValue).toArray());
                list.add(row);
            }
            tag.put("pending", list);
            return tag;
        }
    }
}
