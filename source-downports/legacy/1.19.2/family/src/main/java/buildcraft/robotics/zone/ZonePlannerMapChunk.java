/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.robotics.zone;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MaterialColor;

public class ZonePlannerMapChunk {
    private final MapColourData[][] data = new MapColourData[16][16];
    private final boolean available;

    /** Creates a negative-cache entry for a chunk that is not currently loaded. */
    public ZonePlannerMapChunk() {
        available = false;
    }

    public ZonePlannerMapChunk(Level world, ZonePlannerMapChunkKey key) {
        LevelChunk chunk = world.getChunkSource().getChunkNow(key.chunkPos.x, key.chunkPos.z);
        available = chunk != null;
        if (chunk == null) {
            return;
        }

        int baseX = key.chunkPos.x << 4;
        int baseZ = key.chunkPos.z << 4;
        SurfaceSample[][] samples = new SurfaceSample[16][16];

        // Only inspect chunks that are already loaded. This keeps the Zone Planner from generating terrain
        // just to paint the map, while still allowing vanilla-style north/south height shading across a
        // chunk edge whenever the northern neighbour is already present.
        LevelChunk northChunk = world.getChunkSource().getChunkNow(key.chunkPos.x, key.chunkPos.z - 1);
        SurfaceSample[] northEdge = northChunk == null ? null : new SurfaceSample[16];

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                samples[localX][localZ] = sampleSurface(world, chunk, localX, localZ, baseX, baseZ);
            }
            if (northEdge != null) {
                northEdge[localX] = sampleSurface(world, northChunk, localX, 15, baseX, baseZ - 16);
            }
        }

        for (int localX = 0; localX < 16; localX++) {
            int worldX = baseX + localX;
            for (int localZ = 0; localZ < 16; localZ++) {
                SurfaceSample current = samples[localX][localZ];
                if (current == null) {
                    continue;
                }

                SurfaceSample previous = localZ == 0
                        ? (northEdge == null ? null : northEdge[localX])
                        : samples[localX][localZ - 1];
                int worldZ = baseZ + localZ;
                MaterialColor.Brightness brightness = getVanillaBrightness(current, previous, worldX, worldZ);
                int nativeMapColour = current.mapColor.calculateRGBColor(brightness);
                data[localX][localZ] = new MapColourData(current.posY, mapNativeToArgb(nativeMapColour));
            }
        }
    }

    @Nullable
    private static SurfaceSample sampleSurface(Level world, LevelChunk chunk, int localX, int localZ,
            int baseX, int baseZ) {
        int maxY = world.getMaxBuildHeight() - 1;
        int minY = world.getMinBuildHeight();
        int topY = Math.min(maxY, Math.max(minY,
                chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ)));
        int worldX = baseX + localX;
        int worldZ = baseZ + localZ;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, topY, worldZ);

        for (int y = topY; y >= minY; y--) {
            pos.setY(y);
            BlockState state = getMapState(world, pos, chunk.getBlockState(pos));
            MaterialColor mapColor = state.getMapColor(world, pos);
            if (mapColor == null || mapColor == MaterialColor.NONE) {
                continue;
            }

            int waterDepth = mapColor == MaterialColor.WATER
                    ? countFluidDepth(chunk, worldX, y, worldZ, minY)
                    : 0;
            return new SurfaceSample(y, mapColor, waterDepth);
        }
        return null;
    }

    /**
     * Matches MapItem's treatment of waterlogged/non-solid blocks: when their upper face does not hide
     * the fluid, the map uses the fluid's legacy block state and therefore its map colour.
     */
    private static BlockState getMapState(Level world, BlockPos pos, BlockState state) {
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !state.isFaceSturdy(world, pos, Direction.UP)) {
            return fluid.createLegacyBlock();
        }
        return state;
    }

    private static int countFluidDepth(LevelChunk chunk, int worldX, int topY, int worldZ, int minY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, topY, worldZ);
        int depth = 0;
        for (int y = topY; y >= minY; y--) {
            pos.setY(y);
            if (chunk.getFluidState(pos).isEmpty()) {
                break;
            }
            depth++;
        }
        return depth;
    }

    private static MaterialColor.Brightness getVanillaBrightness(
            SurfaceSample current, @Nullable SurfaceSample previous, int worldX, int worldZ) {
        int checker = (worldX + worldZ) & 1;
        if (current.mapColor == MaterialColor.WATER) {
            double waterShade = current.waterDepth * 0.1D + checker * 0.2D;
            if (waterShade < 0.5D) {
                return MaterialColor.Brightness.HIGH;
            }
            if (waterShade > 0.9D) {
                return MaterialColor.Brightness.LOW;
            }
            return MaterialColor.Brightness.NORMAL;
        }

        int previousY = previous == null ? current.posY : previous.posY;
        double slopeShade = current.posY - previousY + (checker - 0.5D) * 0.4D;
        if (slopeShade > 0.6D) {
            return MaterialColor.Brightness.HIGH;
        }
        if (slopeShade < -0.6D) {
            return MaterialColor.Brightness.LOW;
        }
        return MaterialColor.Brightness.NORMAL;
    }

    /**
     * Map colours are produced in the native byte order used by Minecraft's map texture. The Zone Planner
     * keeps colours as ARGB while they travel through the network and while the zone overlay is blended,
     * then GuiZonePlanner converts them back exactly once when writing the NativeImage.
     */
    private static int mapNativeToArgb(int nativeMapColour) {
        int alpha = nativeMapColour & 0xFF_00_00_00;
        int red = (nativeMapColour & 0x00_00_00_FF) << 16;
        int green = nativeMapColour & 0x00_00_FF_00;
        int blue = (nativeMapColour & 0x00_FF_00_00) >> 16;
        return alpha | red | green | blue;
    }

    public ZonePlannerMapChunk(FriendlyByteBuf buffer) {
        available = buffer.readBoolean();
        if (!available) {
            return;
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (buffer.readBoolean()) {
                    int posY = buffer.readInt();
                    int colour = buffer.readInt();
                    data[x][z] = new MapColourData(posY, colour);
                }
            }
        }
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBoolean(available);
        if (!available) {
            return;
        }
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                MapColourData colour = data[x][z];
                buffer.writeBoolean(colour != null);
                if (colour != null) {
                    buffer.writeInt(colour.posY);
                    buffer.writeInt(colour.colour);
                }
            }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public int getColour(int x, int z) {
        MapColourData col = getData(x, z);
        return col == null ? -1 : col.colour;
    }

    @Nullable
    public MapColourData getData(int x, int z) {
        return data[x & 15][z & 15];
    }

    private static final class SurfaceSample {
        private final int posY;
        private final MaterialColor mapColor;
        private final int waterDepth;

        private SurfaceSample(int posY, MaterialColor mapColor, int waterDepth) {
            this.posY = posY;
            this.mapColor = mapColor;
            this.waterDepth = waterDepth;
        }
    }

    public static final class MapColourData {
        public final int posY;
        public final int colour;

        public MapColourData(int posY, int colour) {
            this.posY = posY;
            this.colour = colour;
        }
    }
}
