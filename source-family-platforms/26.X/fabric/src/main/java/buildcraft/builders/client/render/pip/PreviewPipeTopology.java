/*
 * Copyright (c) 2026 BuildCraft Community Edition contributors
 * Read-only schematic adjacency for the offline pipe preview. No world lookups.
 */
package buildcraft.builders.client.render.pip;

import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;

import buildcraft.builders.client.render.ClientArchitectPreview;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.transport.pipe.SchematicBlockPipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Schematic `con` is purposely stripped by BlueprintSecurity: it describes a
 * previous world's connections, not the future structure. Reconstruct only
 * pipe-to-pipe arms from the blueprint's neighbours, using the real pipe
 * compatibility rule. External machines are unknown and are not guessed.
 */
public final class PreviewPipeTopology {
    private final Blueprint blueprint;
    private final Map<ISchematicBlock, CompoundTag> nbtCache = new IdentityHashMap<>();
    private final Map<ISchematicBlock, PreviewPipeModel.LinkProfile> linkCache = new IdentityHashMap<>();

    public record Neighbours(Map<Direction, CompoundTag> tiles, int connectionMask) {}

    public PreviewPipeTopology(Blueprint blueprint) {
        this.blueprint = blueprint;
    }

    public CompoundTag tileData(ISchematicBlock schematic) {
        if (schematic == null) return null;
        if (!nbtCache.containsKey(schematic)) {
            CompoundTag tile = null;
            if (schematic instanceof SchematicBlockPipe pipe) {
                tile = NbtCompat.getCompound(pipe.serializeNBT(), "tileNbt");
            } else if (schematic instanceof ClientArchitectPreview.RenderBlock live) {
                tile = live.pipeRenderNbt();
            }
            nbtCache.put(schematic, tile);
        }
        return nbtCache.get(schematic);
    }

    private PreviewPipeModel.LinkProfile links(ISchematicBlock cell) {
        if (!linkCache.containsKey(cell)) {
            linkCache.put(cell, PreviewPipeModel.links(tileData(cell)));
        }
        return linkCache.get(cell);
    }

    public Neighbours around(BlockPos cellPos, ISchematicBlock cell, CompoundTag tileNbt) {
        EnumMap<Direction, CompoundTag> neighbours = new EnumMap<>(Direction.class);
        CompoundTag pipe = NbtCompat.getCompound(tileNbt, "pipe");
        boolean hasSavedMask = NbtCompat.contains(pipe, "con", Tag.TAG_INT);
        int mask = hasSavedMask ? NbtCompat.getInt(pipe, "con") : 0;
        PreviewPipeModel.LinkProfile own = hasSavedMask ? null : links(cell);
        for (Direction side : Direction.values()) {
            ISchematicBlock neighbourCell = blockAt(cellPos.relative(side));
            if (neighbourCell == null) continue;
            CompoundTag neighbourTile = tileData(neighbourCell);
            if (neighbourTile == null || NbtCompat.getString(
                NbtCompat.getCompound(neighbourTile, "pipe"), "def").isEmpty()) continue;
            neighbours.put(side, neighbourTile);
            if (!hasSavedMask && PreviewPipeModel.canConnect(own, links(neighbourCell), side)) {
                mask |= 1 << (side.ordinal() * 2); // PIPE connection, not an invented TILE connection
            }
        }
        return new Neighbours(neighbours, mask);
    }

    private ISchematicBlock blockAt(BlockPos at) {
        if (blueprint.size == null || blueprint.data == null || at.getX() < 0 || at.getY() < 0
            || at.getZ() < 0 || at.getX() >= blueprint.size.getX()
            || at.getY() >= blueprint.size.getY() || at.getZ() >= blueprint.size.getZ()) return null;
        int index = Snapshot.posToIndex(blueprint.size, at.getX(), at.getY(), at.getZ());
        if (index < 0 || index >= blueprint.data.length) return null;
        int palette = blueprint.data[index];
        if (palette < 0 || palette >= blueprint.palette.size()) return null;
        return blueprint.palette.get(palette);
    }
}
