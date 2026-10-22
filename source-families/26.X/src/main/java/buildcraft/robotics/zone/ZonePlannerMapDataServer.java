//? source if >=26.2
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics.zone;

import net.minecraft.world.level.Level;

public class ZonePlannerMapDataServer extends ZonePlannerMapData {
    public static final ZonePlannerMapDataServer INSTANCE = new ZonePlannerMapDataServer();

    public ZonePlannerMapChunk loadChunk(Level world, ZonePlannerMapChunkKey key) {
        if (world.getChunkSource().getChunkNow(key.chunkPos.x(), key.chunkPos.z()) == null) {
            return null;
        }
        return new ZonePlannerMapChunk(world, key);
    }
}
