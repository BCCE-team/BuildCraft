//? source if >=1.21.10
/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * Client-only area preview inspired by BuildCraft Redux (MPL-2.0).
 */
package buildcraft.builders.client.render;

import java.util.HashMap;
import java.util.Map;

import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.SchematicBlockContext;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.SchematicBlockAir;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.tile.TileArchitectTable;
import buildcraft.lib.internal.core.InvalidInputDataException;
import buildcraft.transport.block.BlockPipeHolder;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.tile.TilePipeHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renders the *current* Architect Table selection before it has been scanned into a blueprint.
 * This is deliberately not a schematic capture: it reads only client-visible BlockState and
 * pipe render data, doesn't serialize inventories and never sends anything to a server.
 * A large area is sampled in the same order/stride as the off-screen renderer, in small batches
 * on client ticks. No chunk loads, off-thread level access or per-frame full-volume scans.
 */
public final class ClientArchitectPreview {
    private static final int MAX_VOLUME = 2_097_152;
    private static final int MAX_DIMENSION = 512;
    private static final int MAX_DISPLAY_CELLS = 8_192;
    private static final int CELLS_PER_TICK = 512;
    private static final int REFRESH_INTERVAL = 40;

    private static TileArchitectTable activeTile;
    private static Level activeLevel;
    private static BlockPos lastMin, lastMax;
    private static Blueprint displayed;
    private static Scan scan;
    private static int refreshCountdown;

    private ClientArchitectPreview() {}

    public static Snapshot get(TileArchitectTable tile) {
        if (tile == null || tile != activeTile || tile.getLevel() != activeLevel) return null;
        return displayed;
    }

    public static void clear(TileArchitectTable tile) {
        if (tile != activeTile) return;
        activeTile = null;
        activeLevel = null;
        lastMin = null;
        lastMax = null;
        displayed = null;
        scan = null;
        refreshCountdown = 0;
    }

    public static void tick(TileArchitectTable tile) {
        Level level = Minecraft.getInstance().level;
        if (tile == null || level == null || tile.getLevel() != level || !tile.box.isInitialized()) {
            clear(activeTile);
            return;
        }
        BlockPos min = tile.box.min(), max = tile.box.max();
        if (tile != activeTile || level != activeLevel || !min.equals(lastMin) || !max.equals(lastMax)) {
            activeTile = tile;
            activeLevel = level;
            lastMin = min.immutable();
            lastMax = max.immutable();
            displayed = null;
            scan = null;
            refreshCountdown = 0;
        }
        if (scan == null && refreshCountdown-- <= 0) {
            scan = Scan.start(level, lastMin, lastMax);
            refreshCountdown = REFRESH_INTERVAL;
        }
        if (scan == null) return;
        scan.advance(level);
        if (scan.complete()) {
            displayed = scan.result;
            scan = null;
        }
    }

    private static final class Scan {
        final BlockPos min;
        final Blueprint result = new Blueprint();
        final Map<BlockState, Integer> sharedStates = new HashMap<>();
        final int length;
        final int step;
        int next;

        private Scan(BlockPos min, BlockPos size, int volume) {
            this.min = min;
            this.length = volume;
            this.step = Math.max(1, (volume + MAX_DISPLAY_CELLS - 1) / MAX_DISPLAY_CELLS);
            result.size = size;
            result.facing = Direction.NORTH;
            result.offset = BlockPos.ZERO;
            result.data = new int[volume];
            result.palette.add(new SchematicBlockAir()); // palette index zero is always air
        }

        static Scan start(Level level, BlockPos min, BlockPos max) {
            long sx = (long) max.getX() - min.getX() + 1;
            long sy = (long) max.getY() - min.getY() + 1;
            long sz = (long) max.getZ() - min.getZ() + 1;
            if (sx <= 0 || sy <= 0 || sz <= 0 || sx > MAX_DIMENSION || sy > MAX_DIMENSION
                || sz > MAX_DIMENSION || sx * sy * sz > MAX_VOLUME) return null;
            return new Scan(min, new BlockPos((int) sx, (int) sy, (int) sz), (int) (sx * sy * sz));
        }

        void advance(Level level) {
            for (int count = 0; count < CELLS_PER_TICK && next < length; count++, next += step) {
                BlockPos relative = result.indexToPos(next);
                BlockPos worldPos = min.offset(relative);
                if (!level.hasChunkAt(worldPos)) continue;
                BlockState state = level.getBlockState(worldPos);
                if (state.isAir()) continue;
                int paletteId;
                if (state.getBlock() instanceof BlockPipeHolder
                    && level.getBlockEntity(worldPos) instanceof TilePipeHolder pipeTile
                    && pipeTile.getPipe() != null && pipeTile.getPipe() != Pipe.EMPTY) {
                    // Pipes of the same block state may have different types, colours or attachments.
                    // Never share a palette entry between them.
                    CompoundTag nbt;
                    try {
                        nbt = pipeRenderNbt(pipeTile);
                    } catch (RuntimeException | LinkageError invalidAddonState) {
                        // A malformed/missing addon must not abort the entire live preview scan.
                        nbt = null;
                    }
                    paletteId = result.palette.size();
                    result.palette.add(new RenderBlock(state, nbt));
                } else {
                    paletteId = sharedStates.computeIfAbsent(state, key -> {
                        int id = result.palette.size();
                        result.palette.add(new RenderBlock(key, null));
                        return id;
                    });
                }
                result.data[next] = paletteId;
            }
        }

        boolean complete() { return next >= length; }

        private static CompoundTag pipeRenderNbt(TilePipeHolder tile) {
            CompoundTag out = new CompoundTag();
            Pipe pipe = tile.getPipe();
            CompoundTag saved = pipe.writeToNbt();
            // Client pipe pick-block NBT intentionally omits live connection state, but the
            // preview should still show its visible arms. Reconstruct only the 2-bit mask.
            int connected = 0;
            for (Direction side : Direction.values()) {
                if (pipe.isConnected(side)) connected |= 1 << (side.ordinal() * 2);
            }
            saved.putInt("con", connected);
            out.put("pipe", saved);
            CompoundTag plugs = new CompoundTag();
            for (Direction side : Direction.values()) {
                PipePluggable plug = tile.getPluggable(side);
                if (plug == null || plug == PipePluggable.EMPTY || plug.definition == null) continue;
                CompoundTag value = new CompoundTag();
                value.putString("id", plug.definition.identifier.toString());
                value.put("data", plug.writeToNbt());
                plugs.put(side.getName(), value);
            }
            if (!plugs.isEmpty()) out.put("plugs", plugs);
            // The wire graph itself is server-owned. Copy only the visible, local wire
            // parts for a client preview; the neighbour segments are inferred from
            // other sampled cells. This is never submitted as a playable schematic.
            CompoundTag wireState = new CompoundTag();
            int[] parts = new int[tile.wireManager.parts.size() * 2];
            int pos = 0;
            for (var entry : tile.wireManager.parts.entrySet()) {
                parts[pos++] = entry.getKey().ordinal();
                parts[pos++] = entry.getValue().getId();
            }
            wireState.putIntArray("parts", parts);
            int[] lit = tile.wireManager.poweredClient.stream().mapToInt(part -> part.ordinal()).toArray();
            wireState.putIntArray("previewPowered", lit);
            out.put("wireManager", wireState);
            return out;
        }
    }

    /** Schematic-shaped *render hint*; every placement/capture method is intentionally disabled. */
    public static final class RenderBlock implements ISchematicBlock {
        private final BlockState state;
        private final CompoundTag pipeData;
        private RenderBlock(BlockState state, CompoundTag pipeData) {
            this.state = state;
            this.pipeData = pipeData;
        }
        @Override public BlockState getBlockStateForRender() { return state; }
        public CompoundTag pipeRenderNbt() { return pipeData; }
        @Override public void init(SchematicBlockContext context) {
            throw new UnsupportedOperationException("Render-only block cannot capture a schematic");
        }
        @Override public RenderBlock getRotated(Rotation rotation) { return this; }
        @Override public boolean canBuild(Level level, BlockPos pos) { return false; }
        @Override public boolean build(Level level, BlockPos pos) { return false; }
        @Override public boolean buildWithoutChecks(Level level, BlockPos pos) { return false; }
        @Override public boolean isBuilt(Level level, BlockPos pos) { return false; }
        @Override public CompoundTag serializeNBT() {
            throw new UnsupportedOperationException("Live Architect preview must not be serialized");
        }
        @Override public void deserializeNBT(CompoundTag nbt) throws InvalidInputDataException {
            throw new InvalidInputDataException("Live Architect preview cannot be deserialized");
        }
    }
}
