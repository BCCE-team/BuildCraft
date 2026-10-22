/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.logic.zone;

/** Pure block/chunk coordinate math shared by zones and map planning. */
public final class ChunkGridMath {
    public static final int CHUNK_SIZE = 16;

    private ChunkGridMath() {}

    public static int chunkCoordinate(int blockCoordinate) {
        return Math.floorDiv(blockCoordinate, CHUNK_SIZE);
    }

    public static int localCoordinate(int blockCoordinate) {
        return Math.floorMod(blockCoordinate, CHUNK_SIZE);
    }

    public static int minBlock(int chunkCoordinate) {
        return chunkCoordinate * CHUNK_SIZE;
    }

    public static int centerBlock(int chunkCoordinate) {
        return minBlock(chunkCoordinate) + CHUNK_SIZE / 2;
    }

    public static double squaredDistanceToChunkCenter(int blockX, int blockZ, int chunkX, int chunkZ) {
        double dx = centerBlock(chunkX) - blockX;
        double dz = centerBlock(chunkZ) - blockZ;
        return dx * dx + dz * dz;
    }
}
