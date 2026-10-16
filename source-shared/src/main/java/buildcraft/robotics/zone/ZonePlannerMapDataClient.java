/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics.zone;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.Set;

import buildcraft.lib.net.MessageManager;
import net.minecraft.world.level.Level;

public class ZonePlannerMapDataClient extends ZonePlannerMapData {
    public static final ZonePlannerMapDataClient INSTANCE = new ZonePlannerMapDataClient();

    private static final int REQUESTS_PER_TICK = 8;
    private static final int MAX_IN_FLIGHT = 64;
    private static final int MAX_QUEUED = 4096;
    private final Set<ZonePlannerMapChunkKey> pending = new HashSet<>();
    private final Map<ZonePlannerMapChunkKey, Long> sentAtTick = new HashMap<>();
    private final Set<ZonePlannerMapChunkKey> queued = new LinkedHashSet<>();
    private long lastSentTick = Long.MIN_VALUE;
    private int requestsThisTick;
    private long revision;

    @Override
    public ZonePlannerMapChunk loadChunk(Level world, ZonePlannerMapChunkKey key) {
        if (!pending.contains(key) && queued.size() < MAX_QUEUED) {
            queued.add(key);
        }
        return null;
    }

    /** Called once per render while the planner is open. Never send per-pixel requests. */
    public void flushRequests(Level world) {
        if (world == null) return;
        long tick = world.getGameTime();
        if (lastSentTick != tick) {
            lastSentTick = tick;
            requestsThisTick = 0;
            // Invalid/denied requests are not necessarily answered by the
            // server. Do not permanently fill the in-flight window.
            pending.removeIf(key -> {
                long started = sentAtTick.getOrDefault(key, tick);
                return tick < started || tick - started >= 200;
            });
            sentAtTick.keySet().retainAll(pending);
        }
        while (requestsThisTick < REQUESTS_PER_TICK && pending.size() < MAX_IN_FLIGHT
                && !queued.isEmpty()) {
            Iterator<ZonePlannerMapChunkKey> cursor = queued.iterator();
            ZonePlannerMapChunkKey key = cursor.next();
            cursor.remove();
            if (pending.add(key)) {
                sentAtTick.put(key, tick);
                MessageManager.sendToServer(new MessageZoneMapRequest(key));
                requestsThisTick++;
            }
        }
    }


    public boolean isChunkAvailable(ZonePlannerMapChunkKey key) {
        ZonePlannerMapChunk chunk = data.getIfPresent(key);
        return chunk != null && chunk.isAvailable();
    }

    public void clearCache() {
        pending.clear();
        sentAtTick.clear();
        queued.clear();
        lastSentTick = Long.MIN_VALUE;
        requestsThisTick = 0;
        data.invalidateAll();
        revision++;
    }

    public void onChunkReceived(ZonePlannerMapChunkKey key, ZonePlannerMapChunk zonePlannerMapChunk) {
        pending.remove(key);
        sentAtTick.remove(key);
        queued.remove(key);
        data.put(key, zonePlannerMapChunk);
        revision++;
    }

    public long getRevision() {
        return revision;
    }
}
