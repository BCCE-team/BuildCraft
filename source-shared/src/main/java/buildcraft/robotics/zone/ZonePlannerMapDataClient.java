/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics.zone;

import java.util.HashSet;
import java.util.Set;

import buildcraft.lib.net.BCNetwork;
import net.minecraft.world.level.Level;

public class ZonePlannerMapDataClient extends ZonePlannerMapData {
    public static final ZonePlannerMapDataClient INSTANCE = new ZonePlannerMapDataClient();

    private final Set<ZonePlannerMapChunkKey> pending = new HashSet<>();
    private long revision;

    @Override
    public ZonePlannerMapChunk loadChunk(Level world, ZonePlannerMapChunkKey key) {
        if (pending.add(key)) {
            BCNetwork.sendToServer(new MessageZoneMapRequest(key));
        }
        return null;
    }


    public boolean isChunkAvailable(ZonePlannerMapChunkKey key) {
        ZonePlannerMapChunk chunk = data.getIfPresent(key);
        return chunk != null && chunk.isAvailable();
    }

    public void clearCache() {
        pending.clear();
        data.invalidateAll();
        revision++;
    }

    public void onChunkReceived(ZonePlannerMapChunkKey key, ZonePlannerMapChunk zonePlannerMapChunk) {
        pending.remove(key);
        data.put(key, zonePlannerMapChunk);
        revision++;
    }

    public long getRevision() {
        return revision;
    }
}
