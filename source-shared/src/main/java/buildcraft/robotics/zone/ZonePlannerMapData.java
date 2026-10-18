/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics.zone;

import java.util.concurrent.TimeUnit;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import net.minecraft.world.level.Level;

public abstract class ZonePlannerMapData {
    protected final Cache<ZonePlannerMapChunkKey, ZonePlannerMapChunk> data = CacheBuilder.newBuilder()
            .expireAfterWrite(2, TimeUnit.MINUTES)
            .build();

    /** Use {@link #getChunk(World, ZonePlannerMapChunkKey)} for a cached version */
    protected abstract ZonePlannerMapChunk loadChunk(Level world, ZonePlannerMapChunkKey key);

    /** Lets the client refresh negative entries without invalidating the entire map cache. */
    protected boolean mayReuseChunk(Level world, ZonePlannerMapChunkKey key, ZonePlannerMapChunk cached) {
        return true;
    }

    public final ZonePlannerMapChunk getChunk(Level world, ZonePlannerMapChunkKey key) {
        ZonePlannerMapChunk cached = data.getIfPresent(key);
        if (cached != null) {
            if (mayReuseChunk(world, key, cached)) return cached;
            data.invalidate(key);
        }
        ZonePlannerMapChunk zonePlannerMapChunk = loadChunk(world, key);
        if (zonePlannerMapChunk != null) {
            data.put(key, zonePlannerMapChunk);
            return zonePlannerMapChunk;
        }
        return null;
    }
}
