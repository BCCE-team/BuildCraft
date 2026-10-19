/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.marker;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import buildcraft.core.BCCoreConfig;
import buildcraft.core.client.BuildCraftLaserManager;
import buildcraft.lib.client.render.laser.LaserData_BC8.LaserType;
import buildcraft.lib.marker.MarkerCache;
import buildcraft.lib.marker.MarkerSubCache;
import buildcraft.lib.net.MessageMarker;
import com.google.common.collect.ImmutableList;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public class PathSubCache extends MarkerSubCache<PathConnection> {
    public PathSubCache(Level world) {
        super(world, MarkerCache.CACHES.indexOf(PathCache.INSTANCE));
        this.pathWorld = world;
        PathSavedData data;
        if (world instanceof ServerLevel sworld) {
            data = sworld.getDataStorage().computeIfAbsent(PathSavedData::new, PathSavedData::new, PathSavedData.NAME);
        } else {
            data = new PathSavedData();
        }
        data.loadInto(this);
        setDirty(false);
    }

    @Override
    public boolean tryConnect(BlockPos from, BlockPos to) {
        PathConnection conFrom = getConnection(from);
        PathConnection conTo = getConnection(to);
        if (conFrom == null) {
            if (conTo == null) {
                return PathConnection.tryCreateConnection(this, from, to);
            } else {
                return conTo.addMarker(from, to);
            }
        } else {
            if (conTo == null) {
                return conFrom.addMarker(from, to);
            } else {
                return conFrom.mergeWith(conTo, from, to);
            }
        }
    }

    @Override
    public boolean canConnect(BlockPos from, BlockPos to) {
        PathConnection conFrom = getConnection(from);
        PathConnection conTo = getConnection(to);
        if (conFrom == null) {
            if (conTo == null) {
                return true;
            } else {
                return conTo.canAddMarker(from, to);
            }
        } else {
            if (conTo == null) {
                return conFrom.canAddMarker(from, to);
            } else {
                return conFrom.canMergeWith(conTo, from, to);
            }
        }
    }

    /** One spatial index per tick rather than one full scan of all markers for each preview anchor. */
    private final Map<Long, List<BlockPos>> candidateChunks = new HashMap<>();
    private final Map<BlockPos, ImmutableList<BlockPos>> candidateResults = new HashMap<>();
    private long candidatesTick = Long.MIN_VALUE;
    private final Level pathWorld;

    @Override
    public void setDirty(boolean dirty) {
        super.setDirty(dirty);
        if (dirty && candidateChunks != null) {
            candidateChunks.clear();
            candidateResults.clear();
            candidatesTick = Long.MIN_VALUE;
        }
    }

    private static long chunkIndex(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    @Override
    public ImmutableList<BlockPos> getValidConnections(BlockPos from) {
        long time = pathWorld.getGameTime();
        if (time != candidatesTick) {
            candidateChunks.clear();
            candidateResults.clear();
            candidatesTick = time;
            for (BlockPos position : getAllMarkers()) {
                candidateChunks.computeIfAbsent(chunkIndex(position.getX() >> 4, position.getZ() >> 4),
                    ignored -> new ArrayList<>()).add(position);
            }
        }
        ImmutableList<BlockPos> cached = candidateResults.get(from);
        if (cached != null) return cached;
        ImmutableList.Builder<BlockPos> list = ImmutableList.builder();
        int distance = Math.max(0, BCCoreConfig.markerMaxDistance);
        long maxLengthSquared = (long) distance * distance;
        int minChunkX = (from.getX() - distance) >> 4;
        int maxChunkX = (from.getX() + distance) >> 4;
        int minChunkZ = (from.getZ() - distance) >> 4;
        int maxChunkZ = (from.getZ() + distance) >> 4;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                List<BlockPos> candidates = candidateChunks.get(chunkIndex(x, z));
                if (candidates == null) continue;
                for (BlockPos pos : candidates) {
                    if (pos.equals(from) || pos.distSqr(from) > maxLengthSquared) continue;
                    if (canConnect(from, pos) || canConnect(pos, from)) list.add(pos);
                }
            }
        }
        ImmutableList<BlockPos> result = list.build();
        candidateResults.put(from, result);
        return result;
    }

    public LaserType getPossibleLaserType() {
        return BuildCraftLaserManager.MARKER_PATH_POSSIBLE;
    }

    @Override
    protected boolean handleMessage(MessageMarker message) {
        List<BlockPos> positions = message.positions;
        if (message.connection) {
            if (message.add) {
                for (BlockPos p : positions) {
                    PathConnection existing = this.getConnection(p);
                    destroyConnection(existing);
                }
                PathConnection con = new PathConnection(this, positions);
                addConnection(con);
            } else { // removing from a connection
                for (BlockPos p : positions) {
                    PathConnection existing = this.getConnection(p);
                    if (existing != null) {
                        // removeMarker already refreshes end removals, and handles
                        // middle removals by destroying the old connection.
                        existing.removeMarker(p);
                    }
                }
            }
            return true;
        }
        return false;
    }
}
