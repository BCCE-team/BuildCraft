/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public class ChunkUtil {

    public static LevelChunk getChunk(Level Level, BlockPos pos, boolean force) {
        return getChunk(Level, pos.getX() >> 4, pos.getZ() >> 4, force);
    }

    public static LevelChunk getChunk(Level Level, ChunkPos pos, boolean force) {
        return getChunk(Level, pos.x, pos.z, force);
    }

    public static LevelChunk getChunk(Level level, int x, int z, boolean force) {
        // Do not cache chunks in a static ThreadLocal: a replaced or unloaded chunk
        // can otherwise be retained and returned across worlds on a reused thread.
        if (!force && level instanceof ServerLevel serverLevel) {
            return serverLevel.getChunkSource().getChunkNow(x, z);
        }
        if (!force && !level.hasChunk(x, z)) return null;
        return level.getChunk(x, z);
    }
}
