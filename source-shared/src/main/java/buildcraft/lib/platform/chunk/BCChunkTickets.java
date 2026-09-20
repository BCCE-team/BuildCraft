/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.platform.chunk;

import buildcraft.lib.chunkload.IChunkLoadingTile;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.chunkload.IChunkLoadingTile.LoadType;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;


/**
 * Internal lifecycle controller for BuildCraft's machine chunk tickets.
 *
 * <p>Tickets are persisted by the loader and owned by the loading block's position. The local mirror is used to release
 * chunks when a quarry changes size.</p>
 */
public final class BCChunkTickets {
    private static final Map<ServerLevel, Map<BlockPos, Set<ChunkPos>>> LOADED_CHUNKS = new WeakHashMap<>();

    private BCChunkTickets() {
    }

    /** Registers the Forge persisted-ticket validation callback. */
    public static void init() { PlatformChunkTickets.init(); }

    /** Applies the chunks currently requested by a loading tile and releases obsolete tickets. */
    public static <T extends BlockEntity & IChunkLoadingTile> void loadChunksForTile(T tile) {
        if (!(tile.getLevel() instanceof ServerLevel level) || tile.isRemoved()) {
            return;
        }
        loadChunksForTile(level, tile, tile);
    }

    private static void loadChunksForTile(ServerLevel level, BlockEntity owner, IChunkLoadingTile loadingTile) {
        BlockPos ownerPos = owner.getBlockPos().immutable();
        if (!canLoadFor(level, loadingTile)) {
            releaseChunksFor(level, ownerPos, getTrackedChunks(level, ownerPos));
            return;
        }

        Set<ChunkPos> wanted = getChunksToLoad(owner, loadingTile);
        Map<BlockPos, Set<ChunkPos>> levelChunks = LOADED_CHUNKS.computeIfAbsent(level, ignored -> new HashMap<>());
        Set<ChunkPos> previous = levelChunks.getOrDefault(ownerPos, Collections.emptySet());

        Set<ChunkPos> obsolete = new HashSet<>(previous);
        obsolete.removeAll(wanted);
        for (ChunkPos chunk : obsolete) {
            unforceChunk(level, ownerPos, chunk);
        }

        for (ChunkPos chunk : wanted) {
            // Remove a legacy non-ticking form before adding the fully ticking machine ticket.
            PlatformChunkTickets.forceChunk(level, ownerPos, chunk, false, false);
            PlatformChunkTickets.forceChunk(level, ownerPos, chunk, true, true);
        }

        levelChunks.put(ownerPos, new HashSet<>(wanted));
    }

    /** Releases every ticket currently associated with this tile. */
    public static <T extends BlockEntity & IChunkLoadingTile> void releaseChunksFor(T tile) {
        if (!(tile.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos ownerPos = tile.getBlockPos().immutable();
        Set<ChunkPos> chunks = new HashSet<>(getTrackedChunks(level, ownerPos));
        // Also covers a newly placed tile that has not yet been mirrored in LOADED_CHUNKS.
        chunks.addAll(getChunksToLoad(tile, tile));
        releaseChunksFor(level, ownerPos, chunks);
    }

    /** World unload is not machine removal: keep persisted tickets, discard only in-memory references. */
    public static void unloadWorld(ServerLevel level) { LOADED_CHUNKS.remove(level); }

    private static Set<ChunkPos> getTrackedChunks(ServerLevel level, BlockPos ownerPos) {
        Map<BlockPos, Set<ChunkPos>> levelChunks = LOADED_CHUNKS.get(level);
        if (levelChunks == null) {
            return Collections.emptySet();
        }
        return levelChunks.getOrDefault(ownerPos, Collections.emptySet());
    }

    private static void releaseChunksFor(ServerLevel level, BlockPos ownerPos, Set<ChunkPos> chunks) {
        for (ChunkPos chunk : new HashSet<>(chunks)) {
            unforceChunk(level, ownerPos, chunk);
        }
        Map<BlockPos, Set<ChunkPos>> levelChunks = LOADED_CHUNKS.get(level);
        if (levelChunks != null) {
            levelChunks.remove(ownerPos);
            if (levelChunks.isEmpty()) {
                LOADED_CHUNKS.remove(level);
            }
        }
    }

    private static void unforceChunk(ServerLevel level, BlockPos ownerPos, ChunkPos chunk) {
        // Release both compatibility forms so persisted data cannot retain a non-ticking ticket.
        PlatformChunkTickets.forceChunk(level, ownerPos, chunk, false, true);
        PlatformChunkTickets.forceChunk(level, ownerPos, chunk, false, false);
    }

    public static <T extends BlockEntity & IChunkLoadingTile> Set<ChunkPos> getChunksToLoad(T tile) {
        return getChunksToLoad(tile, tile);
    }

    private static Set<ChunkPos> getChunksToLoad(BlockEntity owner, IChunkLoadingTile loadingTile) {
        Set<ChunkPos> requested = loadingTile.getChunksToLoad();
        Set<ChunkPos> chunks = new HashSet<>(requested == null ? Collections.emptySet() : requested);
        chunks.add(new ChunkPos(owner.getBlockPos()));
        return chunks;
    }

    private static boolean canLoadFor(ServerLevel level, IChunkLoadingTile tile) {
        LoadType loadType = tile.getLoadType();
        return loadType != null && isEnabledFor(level) && BCLibConfig.chunkLoadingLevel.canLoad(loadType);
    }

    private static boolean isEnabledFor(ServerLevel level) {
        return switch (BCLibConfig.chunkLoadingType) {
            case ON -> true;
            case AUTO -> !level.getServer().isDedicatedServer();
            case OFF -> false;
        };
    }

    /**
     * Keeps persisted quarry tickets when chunk loading is enabled, so the owner chunk can load and recreate its
     * runtime work-area mirror after a server restart. Tickets are rejected when the current configuration disables
     * hard tile chunk loading.
     */
    public static void validateTickets(ServerLevel level, BCTicketOwners helper) {
        LOADED_CHUNKS.remove(level);
        if (isEnabledFor(level) && BCLibConfig.chunkLoadingLevel.canLoad(LoadType.HARD)) {
            return;
        }

        int removedOwners = 0;
        for (BlockPos owner : new HashSet<>(helper.owners())) {
            helper.removeAllTickets(owner);
            removedOwners++;
        }
        if (removedOwners > 0) {
            BCLog.logger.info(
                "[lib.chunkloading] Removed persisted tickets for {} owner(s) because chunk loading is disabled",
                removedOwners
            );
        }
    }

}
