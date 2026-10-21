/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.marker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
//? if neoforge {
import net.minecraft.resources.ResourceKey;
//? } else {
import net.minecraft.world.level.dimension.DimensionType;
//?}

public abstract class MarkerCache<S extends MarkerSubCache<?>> {
    public static final boolean DEBUG = BCDebugging.shouldDebugLog("lib.markers");
    public static final List<MarkerCache<?>> CACHES = new ArrayList<>();

    public final String name;

//? if neoforge {
    private final Map<ResourceKey<Level>, S> cacheClient = new ConcurrentHashMap<>();
    private final Map<ResourceKey<Level>, S> cacheServer = new ConcurrentHashMap<>();
//? } else {
    private final Map<DimensionType, S> cacheClient = new ConcurrentHashMap<>();
    private final Map<DimensionType, S> cacheServer = new ConcurrentHashMap<>();
//?}

    public MarkerCache(String name) {
        this.name = name;
    }

    public static void registerCache(MarkerCache<?> cache) {
        CACHES.add(cache);
        if (DEBUG) {
            BCLog.logger.info("[lib.markers] Registered a cache " + cache.name + " with an ID of " + (CACHES.size() - 1));
        }
    }

    public static void postInit() {
        if (DEBUG) {
            BCLog.logger.info("[lib.markers] Sorted list of cache types:");
            for (int i = 0; i < CACHES.size(); i++) {
                final MarkerCache<?> cache = CACHES.get(i);
                BCLog.logger.info("  " + i + " = " + cache.name);
            }
            BCLog.logger.info("[lib.markers] Total of " + CACHES.size() + " cache types");
        }
    }

    public static void onPlayerJoinLevel(ServerPlayer player) {
        for (MarkerCache<?> cache : CACHES) {
        	ServerLevel level = ((net.minecraft.server.level.ServerLevel) player.level());
            cache.getSubCache(level).onPlayerJoinLevel(player);
        }
    }

    public static void onLevelUnload(LevelAccessor levelAccessor) {
        for (MarkerCache<?> cache : CACHES) {
            cache.onLevelUnloadImpl(levelAccessor);
        }
    }

    public static void clearClientCaches() {
        for (MarkerCache<?> cache : CACHES) {
            cache.cacheClient.clear();
        }
    }

    private void onLevelUnloadImpl(LevelAccessor levelAccessor) {
//? if neoforge {
        Map<ResourceKey<Level>, S> cache = levelAccessor.isClientSide() ? cacheClient : cacheServer;
        // A LevelAccessor need not represent a dimension (e.g. during world generation).
        if (levelAccessor instanceof Level level) {
            cache.remove(level.dimension());
        }
//? } else {
        Map<DimensionType, S> cache = levelAccessor.isClientSide() ? cacheClient : cacheServer;
        DimensionType key = levelAccessor.dimensionType();
        cache.remove(key);
//?}
    }

    protected abstract S createSubCache(Level level);

    public S getSubCache(Level level) {
//? if neoforge {
        Map<ResourceKey<Level>, S> cache = level.isClientSide() ? cacheClient : cacheServer;
        ResourceKey<Level> key = level.dimension();
//? } else {
        Map<DimensionType, S> cache = level.isClientSide() ? cacheClient : cacheServer;
        DimensionType key = level.dimensionType();
//?}
        return cache.computeIfAbsent(key, k -> createSubCache(level));
    }
}
