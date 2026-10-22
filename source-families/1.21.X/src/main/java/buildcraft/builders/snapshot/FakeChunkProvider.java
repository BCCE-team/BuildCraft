/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;

public class FakeChunkProvider extends ChunkSource {
    private final FakeWorld world;
    public final Map<ChunkPos, LevelChunk> chunks = new HashMap<>();
    private final LevelLightEngine lightEngine;

    public FakeChunkProvider(FakeWorld world) {
        this.world = world;
        this.lightEngine = new LevelLightEngine(this, true, world.dimensionType().hasSkyLight());
    }

    @Nullable
    public LevelChunk getLoadedChunk(int x, int z) {
        return chunks.computeIfAbsent(new ChunkPos(x, z), pos -> new FakeChunk(world, pos));
    }


    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
        return getLoadedChunk(x, z);
    }

    @Override
    public void tick(BooleanSupplier hasTimeLeft, boolean tickChunks) {
    }

    @Override
    public int getLoadedChunksCount() {
        return chunks.size();
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return lightEngine;
    }

    @Override
    public BlockGetter getLevel() {
        return world;
    }

    @Override
    public String gatherStats() {
        return "fake";
    }

    @Override
    public boolean hasChunk(int x, int z) {
        return true;
    }
}
