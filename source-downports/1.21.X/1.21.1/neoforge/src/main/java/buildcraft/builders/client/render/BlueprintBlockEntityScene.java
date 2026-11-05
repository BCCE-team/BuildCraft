/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.render;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.IdentityHashMap;
import buildcraft.builders.client.render.pip.PreviewPipeTopology;
import buildcraft.builders.client.render.pip.PreviewPipeModel;
import java.util.List;
import net.minecraft.world.entity.Entity;
import java.util.Set;

import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.BlueprintSecurity;
import buildcraft.builders.snapshot.FakeWorld;
import buildcraft.transport.block.BlockPipeHolder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Read-only *preview* scene for 1.21.1 NeoForge. Never places anything in the
 * actual level and never executes ISchematicBlock#buildWithoutChecks: a GUI
 * must not invoke construction behaviour or addon side effects.
 *
 * A real BlockEntityRenderer expects a Level with the right neighbours. For
 * example ChestRenderer needs both double-chest halves, and BedRenderer must
 * see the captured HEAD/FOOT state. FakeWorld supplies precisely that context.
 *
 * Preview scenes are cached per currently viewed snapshot and client level.
 * Only displayed BE cells and their six neighbours are staged, keeping this
 * bounded for enormous blueprints (and preserving pair topology at LOD > 1).
 */
final class BlueprintBlockEntityScene {
    private static WeakReference<Blueprint> cachedBlueprint = new WeakReference<>(null);
    private static WeakReference<ClientLevel> cachedClient = new WeakReference<>(null);
    private static BlueprintBlockEntityScene cachedScene;
    private static int cachedStep;

    private final FakeWorld level;
    // Keep the offline topology and baked mesh variants between GUI frames.
    private final PreviewPipeTopology pipeTopology;
    private final Map<ISchematicBlock, Map<Integer, PreviewPipeModel.Mesh>> pipeMeshes = new IdentityHashMap<>();
    private final List<Entity> previewEntities;
    private final Map<ISchematicBlock, CompoundTag> nbtCache = new IdentityHashMap<>();
    // Don't ask the chunk to re-create a BE for each rendered cell. This also
    // avoids losing a valid BE when its saved optional NBT could not be loaded.
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

    private BlueprintBlockEntityScene(Blueprint blueprint, ClientLevel client, int step) {
        level = new FakeWorld(client);
        pipeTopology = new PreviewPipeTopology(blueprint);
        if (blueprint.data == null) {
            previewEntities = List.of();
            return;
        }
        int total = blueprint.getDataSize();
        Set<Integer> staged = new HashSet<>();
        // The GUI itself samples up to 8192 cells, so do not stage millions of
        // normal blocks just because a few of them have a world-only renderer.
        for (int index = 0; index < total; index += step) {
            BlockPos pos = blueprint.indexToPos(index);
            BlockState state = stateAt(blueprint, index);
            if (state == null || !state.hasBlockEntity() || isItemOnly(state)) continue;
            staged.add(index);
            for (Direction side : Direction.values()) {
                BlockPos neighbour = pos.relative(side);
                if (inside(blueprint, neighbour)) {
                    staged.add(blueprint.posToIndex(neighbour));
                }
            }
        }
        // Stage block states before BE data: ChestRenderer can then inspect both
        // halves even when this frame's render order begins at the right half.
        for (int index : staged) {
            BlockState state = stateAt(blueprint, index);
            if (state == null || state.isAir()) continue;
            BlockPos worldPos = blueprint.indexToPos(index).offset(FakeWorld.BLUEPRINT_OFFSET);
            try {
                level.setBlock(worldPos, state, 0);
            } catch (RuntimeException unsupportedAddonBlock) {
                // One optional mod's invalid state must not poison the preview.
            }
        }
        for (int index : staged) {
            BlockState state = stateAt(blueprint, index);
            if (state == null || !state.hasBlockEntity() || isItemOnly(state)) continue;
            BlockPos worldPos = blueprint.indexToPos(index).offset(FakeWorld.BLUEPRINT_OFFSET);
            ISchematicBlock schematic = schematicAt(blueprint, index);
            try {
                BlockEntity be = level.getBlockEntity(worldPos);
                if (be == null && state.getBlock() instanceof EntityBlock block) {
                    be = block.newBlockEntity(worldPos, state);
                }
                if (be == null) continue;
                be.setLevel(level);
                // Register the vanilla/default entity BEFORE reading optional
                // schematic NBT. Previously one bad load skipped registration,
                // and the renderer then silently hid the entire bed/chest/sign.
                blockEntities.put(blueprint.indexToPos(index).immutable(), be);
                // FakeChunk stores this entity for neighbour queries by vanilla
                // chest/sign/bed BERs. Even if an addon rejects that registration,
                // our local map still allows an independent visible fallback.
                try {
                    level.setBlockEntity(be);
                } catch (RuntimeException invalidFakeChunk) {
                    // Preserve the cell for rendering; unrelated cells survive.
                }
                CompoundTag nbt = schematic == null ? null : savedTileData(schematic);
                if (nbt != null) {
                    try {
                        // Never trust a blueprint's BE id, coordinates,
                        // inventory or privileged server state. The block state
                        // determines the renderer/type, not untrusted NBT.
                        CompoundTag safe = BlueprintSecurity.forBlockEntity(state, nbt);
                        ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
                        if (id != null) safe.putString("id", id.toString());
                        safe.putInt("x", worldPos.getX());
                        safe.putInt("y", worldPos.getY());
                        safe.putInt("z", worldPos.getZ());
                        be.loadWithComponents(safe, level.registryAccess());
                    } catch (RuntimeException invalidSavedData) {
                        // The default BE still exists, so it can render with its
                        // captured BlockState even when the saved data is invalid.
                        // Replace any partially modified BE with a clean default.
                        if (state.getBlock() instanceof EntityBlock block) {
                            BlockEntity fresh = block.newBlockEntity(worldPos, state);
                            if (fresh != null) {
                                fresh.setLevel(level);
                                blockEntities.put(blueprint.indexToPos(index).immutable(), fresh);
                                try {
                                    level.setBlockEntity(fresh);
                                } catch (RuntimeException invalidFakeChunk) {
                                    // Defaults are still available to the GUI.
                                }
                            }
                        }
                    }
                }
            } catch (RuntimeException unsupportedAddonTile) {
                // Failure is limited to this cell; the GUI renderer retains a
                // visible item fallback instead of leaving a hole.
            }
        }
        previewEntities = BlueprintEntityPreview.create(blueprint, level);
    }

    List<Entity> previewEntities() { return previewEntities; }

    PreviewPipeModel.Mesh pipeMeshAt(BlockPos pos, ISchematicBlock schematic) {
        CompoundTag tile = pipeTopology.tileData(schematic);
        if (tile == null) return null;
        int mask = pipeTopology.around(pos, schematic, tile).connectionMask();
        Map<Integer, PreviewPipeModel.Mesh> variants = pipeMeshes.computeIfAbsent(
            schematic, ignored -> new HashMap<>());
        if (!variants.containsKey(mask)) {
            variants.put(mask, PreviewPipeModel.reconstruct(tile, mask));
        }
        return variants.get(mask);
    }

    static BlueprintBlockEntityScene forSnapshot(Blueprint blueprint, ClientLevel client, int step) {
        if (cachedScene == null || cachedBlueprint.get() != blueprint
            || cachedClient.get() != client || cachedStep != step) {
            cachedScene = new BlueprintBlockEntityScene(blueprint, client, step);
            cachedBlueprint = new WeakReference<>(blueprint);
            cachedClient = new WeakReference<>(client);
            cachedStep = step;
        }
        return cachedScene;
    }

    BlockEntity blockEntityAt(BlockPos position) {
        return blockEntities.get(position);
    }

    private CompoundTag savedTileData(ISchematicBlock schematic) {
        if (nbtCache.containsKey(schematic)) return nbtCache.get(schematic);
        CompoundTag result = null;
        try {
            CompoundTag serialized = schematic.serializeNBT();
            if (serialized != null && serialized.contains("tileNbt", Tag.TAG_COMPOUND)) {
                result = serialized.getCompound("tileNbt").copy();
            }
        } catch (RuntimeException badAddonSchema) {
            // A custom codec may not support raw tile data; render its defaults.
        }
        nbtCache.put(schematic, result);
        return result;
    }

    private static ISchematicBlock schematicAt(Blueprint blueprint, int index) {
        if (index < 0 || index >= blueprint.data.length) return null;
        int palette = blueprint.data[index];
        return palette >= 0 && palette < blueprint.palette.size() ? blueprint.palette.get(palette) : null;
    }

    private static BlockState stateAt(Blueprint blueprint, int index) {
        ISchematicBlock schematic = schematicAt(blueprint, index);
        if (schematic == null) return null;
        try {
            return schematic.isAir() ? null : schematic.getBlockStateForRender();
        } catch (RuntimeException badAddonSchematic) {
            return null;
        }
    }

    private static boolean inside(Blueprint blueprint, BlockPos position) {
        return position.getX() >= 0 && position.getX() < blueprint.size.getX()
            && position.getY() >= 0 && position.getY() < blueprint.size.getY()
            && position.getZ() >= 0 && position.getZ() < blueprint.size.getZ();
    }

    private static boolean isItemOnly(BlockState state) {
        // Engines are actual BlockEntities: the real engine BER draws their
        // entire body, type, piston and saved facing. Never stage them as items.
        return state.getBlock() instanceof BlockPipeHolder;
    }
}
