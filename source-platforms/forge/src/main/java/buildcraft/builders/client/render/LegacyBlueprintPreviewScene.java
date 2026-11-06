/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL 2.0. */
package buildcraft.builders.client.render;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.BlueprintSecurity;
import buildcraft.builders.snapshot.FakeWorld;
import buildcraft.transport.BCTransportBlocks;
import buildcraft.transport.pipe.SchematicBlockPipe;
import buildcraft.transport.pipe.Pipe;
import buildcraft.transport.internal.pipe.IPipe;
import buildcraft.transport.internal.pluggable.PipePluggable;
import buildcraft.transport.tile.TilePipeHolder;
import buildcraft.lib.misc.NBTUtilBC;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Cached, client-only Forge world for vanilla block-entity and decoration renderers.
 * Unlike FakeWorld.uploadSnapshot, this does NOT invoke schematic build methods.
 * Only the states needed by sampled BlockEntity models and their six neighbours
 * are staged; untrusted tile/entity NBT is sanitized before display.
 */
final class LegacyBlueprintPreviewScene {
    private static final int MAX_ENTITIES = 256;
    private static WeakReference<Blueprint> lastBlueprint = new WeakReference<>(null);
    private static WeakReference<ClientLevel> lastClientLevel = new WeakReference<>(null);
    private static int lastStep = -1;
    private static LegacyBlueprintPreviewScene lastScene;

    final FakeWorld world;
    final List<Entity> entities;
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final Map<ISchematicBlock, ItemStack> fallbackItems = new IdentityHashMap<>();

    static LegacyBlueprintPreviewScene of(Blueprint blueprint, ClientLevel client, int step) {
        if (lastScene == null || lastBlueprint.get() != blueprint
            || lastClientLevel.get() != client || lastStep != step) {
            lastScene = new LegacyBlueprintPreviewScene(blueprint, client, step);
            lastBlueprint = new WeakReference<>(blueprint);
            lastClientLevel = new WeakReference<>(client);
            lastStep = step;
        }
        return lastScene;
    }

    private LegacyBlueprintPreviewScene(Blueprint blueprint, ClientLevel client, int step) {
        world = new FakeWorld(client);
        if (blueprint.data != null) {
            // Only the detached render scene receives these sanitized NBT copies.
            // The blueprint itself never retains live pipe connection state.
            Map<BlockPos, CompoundTag> pipeNbtForPreview = new HashMap<>();
            // All sampled visible block states belong in the detached world.
            // Only staging BE cells made ordinary neighbours invisible to chest
            // combiners, pipe connection checks, model tint and facade logic.
            Set<Integer> staged = new LinkedHashSet<>();
            int length = Math.min(blueprint.getDataSize(), blueprint.data.length);
            for (int index = 0; index < length; index += step) {
                BlockState state = stateAt(blueprint, index);
                if (state == null || state.isAir()) continue;
                staged.add(index);
                if (!state.hasBlockEntity()) continue;
                BlockPos pos = blueprint.indexToPos(index);
                for (Direction direction : Direction.values()) {
                    BlockPos nearby = pos.relative(direction);
                    if (inside(blueprint, nearby)) staged.add(blueprint.posToIndex(nearby));
                }
            }
            // Chest combiners and bed/sign renderers require neighbours to exist
            // before any BlockEntity or saved NBT is initialized.
            for (int index : staged) {
                BlockState state = stateAt(blueprint, index);
                if (state == null || state.isAir()) continue;
                BlockPos pos = blueprint.indexToPos(index).offset(FakeWorld.BLUEPRINT_OFFSET);
                try {
                    if (pos.getY() >= world.getMinBuildHeight() && pos.getY() < world.getMaxBuildHeight()) {
                        world.setBlock(pos, state, 0);
                    }
                } catch (RuntimeException badAddonState) {
                    // A bad optional block must not hide the rest of the preview.
                }
            }
            for (int index : staged) {
                BlockState state = stateAt(blueprint, index);
                if (state == null || !state.hasBlockEntity()) continue;
                ISchematicBlock schematic = schematicAt(blueprint, index);
                if (schematic == null) continue;
                BlockPos relative = blueprint.indexToPos(index);
                BlockPos pos = relative.offset(FakeWorld.BLUEPRINT_OFFSET);
                try {
                    // Pipe schematics save a tile, not a BlockState. Recover the
                    // holder from sanitized NBT so Forge's real baked model and
                    // the pipe BlockEntityRenderer can both draw it.
                    boolean pipeSchematic = schematic instanceof SchematicBlockPipe;
                    CompoundTag saved = schematic.serializeNBT();
                    BlockEntity be = null;
                    if (pipeSchematic && saved != null && saved.contains("tileNbt", Tag.TAG_COMPOUND)) {
                        try {
                            CompoundTag safePipe = BlueprintSecurity.forPipe(saved.getCompound("tileNbt"));
                            ResourceLocation id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(
                                BCTransportBlocks.PIPE_HOLDER_BE.get());
                            if (id != null) safePipe.putString("id", id.toString());
                            safePipe.putInt("x", pos.getX());
                            safePipe.putInt("y", pos.getY());
                            safePipe.putInt("z", pos.getZ());
                            be = BlockEntity.loadStatic(pos, state, safePipe);
                            if (be instanceof TilePipeHolder holder && holder.getPipe() != Pipe.EMPTY) {
                                pipeNbtForPreview.put(relative.immutable(), safePipe);
                            }
                        } catch (RuntimeException badPipeNbt) { /* render default holder */ }
                    }
                    if (be == null) be = world.getBlockEntity(pos);
                    if (be == null && state.getBlock() instanceof EntityBlock block) {
                        be = block.newBlockEntity(pos, state);
                    }
                    if (be == null) continue;
                    be.setLevel(world);
                    blockEntities.put(relative.immutable(), be);
                    try { world.setBlockEntity(be); } catch (RuntimeException unsupportedChunk) { /* cached locally */ }
                    if (!pipeSchematic && saved != null && saved.contains("tileNbt", Tag.TAG_COMPOUND)) {
                        try {
                            CompoundTag safe = BlueprintSecurity.forBlockEntity(state, saved.getCompound("tileNbt"));
                            ResourceLocation id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
                            if (id != null) safe.putString("id", id.toString());
                            safe.putInt("x", pos.getX());
                            safe.putInt("y", pos.getY());
                            safe.putInt("z", pos.getZ());
                            be.load(safe);
                        } catch (RuntimeException badSavedTile) {
                            // Use a clean BE on partial/failed NBT load.
                            if (state.getBlock() instanceof EntityBlock block) {
                                BlockEntity fresh = block.newBlockEntity(pos, state);
                                if (fresh != null) {
                                    fresh.setLevel(world);
                                    blockEntities.put(relative.immutable(), fresh);
                                    try { world.setBlockEntity(fresh); } catch (RuntimeException ignored) { }
                                }
                            }
                        }
                    }
                } catch (RuntimeException unsupportedAddonBlockEntity) {
                    // The installed block state can still be rendered normally.
                }
            }
            // BlueprintSecurity deliberately discards the live pipe.con mask.
            // Rebuild a render-only connection mask from visible neighbours and
            // reload each affected holder with that mask *after* sanitization.
            // Never tick these pipes or copy the preview mask into a Blueprint.
            restorePreviewPipeConnections(blueprint, step, pipeNbtForPreview);
        }
        entities = createEntities(blueprint, world);
    }

    /**
     * The pipe's dynamic baked model reads the packed connection mask from its
     * Pipe instance. A normal saved Blueprint has no such mask (correctly), and
     * no server pipe ticks run in FakeWorld to reconstruct one. Restore only
     * visible, compatible arms in a detached copy of the holder's NBT.
     */
    private void restorePreviewPipeConnections(Blueprint blueprint, int step,
                                               Map<BlockPos, CompoundTag> previewTags) {
        Map<BlockPos, Integer> masks = new HashMap<>();
        for (BlockPos position : previewTags.keySet()) {
            // Sampling skips some cells in very large blueprints. Do not draw
            // arms towards a pipe that is not actually rendered in the GUI.
            if (blueprint.posToIndex(position) % step != 0) continue;
            if (!(blockEntities.get(position) instanceof TilePipeHolder holder)) continue;
            IPipe pipe = holder.getPipe();
            if (pipe == Pipe.EMPTY) continue;
            int mask = 0;
            for (Direction face : Direction.values()) {
                BlockPos next = position.relative(face);
                if (!inside(blueprint, next) || blueprint.posToIndex(next) % step != 0) continue;
                BlockEntity adjacent = blockEntities.get(next);
                if (adjacent == null || blocksConnection(holder.getPluggable(face))) continue;
                try {
                    if (adjacent instanceof TilePipeHolder other) {
                        if (!previewTags.containsKey(next) || other.getPipe() == Pipe.EMPTY
                            || blocksConnection(other.getPluggable(face.getOpposite()))) continue;
                        if (Pipe.canPipesConnect(face, pipe, other.getPipe())) {
                            mask |= previewConnectionBits(face, true);
                        }
                    } else if (pipe.getBehaviour().shouldForceConnection(face, adjacent)
                        || pipe.getFlow().shouldForceConnection(face, adjacent)
                        || (pipe.getBehaviour().canConnect(face, adjacent)
                            && pipe.getFlow().canConnect(face, adjacent))) {
                        mask |= previewConnectionBits(face, false);
                    }
                } catch (RuntimeException | LinkageError badAddonConnection) {
                    // Missing or incompatible addon tiles must not break the GUI.
                }
            }
            if (mask != 0) masks.put(position, mask);
        }
        // Calculate every mask before replacing holders, so neighbour checks
        // always see the same initial sanitized and detached tile state.
        for (Map.Entry<BlockPos, Integer> entry : masks.entrySet()) {
            BlockPos relative = entry.getKey();
            CompoundTag saved = previewTags.get(relative);
            if (saved == null || !saved.contains("pipe", Tag.TAG_COMPOUND)) continue;
            CompoundTag renderOnly = saved.copy();
            renderOnly.getCompound("pipe").putInt("con", entry.getValue());
            BlockPos absolute = relative.offset(FakeWorld.BLUEPRINT_OFFSET);
            try {
                BlockEntity restored = BlockEntity.loadStatic(absolute,
                    BCTransportBlocks.pipeHolder.get().defaultBlockState(), renderOnly);
                if (!(restored instanceof TilePipeHolder holder) || holder.getPipe() == Pipe.EMPTY) continue;
                restored.setLevel(world);
                blockEntities.put(relative, restored);
                try { world.setBlockEntity(restored); }
                catch (RuntimeException unsupportedChunk) { /* direct GUI renderer still has it */ }
            } catch (RuntimeException | LinkageError badPipeNbt) {
                // Keep the previously initialized, connectionless model.
            }
        }
    }

    private static boolean blocksConnection(PipePluggable plug) {
        return plug != null && plug != PipePluggable.EMPTY && plug.isBlocking();
    }

    /** Pipe's NBT format stores six 2-bit sides: 01=pipe, 10=tile. */
    private static int previewConnectionBits(Direction face, boolean pipeNeighbour) {
        return (pipeNeighbour ? 0b01 : 0b10) << (face.ordinal() * 2);
    }

    BlockEntity blockEntityAt(BlockPos relative) {
        return blockEntities.get(relative);
    }

    BlockState blockStateFor(ISchematicBlock schematic) {
        if (schematic instanceof SchematicBlockPipe) {
            return BCTransportBlocks.pipeHolder.get().defaultBlockState();
        }
        try { return schematic.getBlockStateForRender(); }
        catch (RuntimeException badAddon) { return null; }
    }

    ItemStack fallbackItem(ISchematicBlock schematic, ClientLevel client) {
        return fallbackItems.computeIfAbsent(schematic, cell -> {
            try {
                List<ItemStack> items = cell.computeRequiredItems(client);
                if (items != null) {
                    for (ItemStack item : items) {
                        if (item != null && !item.isEmpty()) return item.copy();
                    }
                }
            } catch (RuntimeException badAddonSchematic) { /* leave blank */ }
            return ItemStack.EMPTY;
        });
    }

    private static ISchematicBlock schematicAt(Blueprint bp, int index) {
        if (bp.data == null || index < 0 || index >= bp.data.length) return null;
        int paletteIndex = bp.data[index];
        if (paletteIndex < 0 || paletteIndex >= bp.palette.size()) return null;
        return bp.palette.get(paletteIndex);
    }

    private static BlockState stateAt(Blueprint bp, int index) {
        ISchematicBlock cell = schematicAt(bp, index);
        if (cell == null || cell.isAir()) return null;
        if (cell instanceof SchematicBlockPipe) {
            return BCTransportBlocks.pipeHolder.get().defaultBlockState();
        }
        try { return cell.getBlockStateForRender(); }
        catch (RuntimeException badSchematic) { return null; }
    }

    private static boolean inside(Blueprint bp, BlockPos pos) {
        return pos.getX() >= 0 && pos.getX() < bp.size.getX()
            && pos.getY() >= 0 && pos.getY() < bp.size.getY()
            && pos.getZ() >= 0 && pos.getZ() < bp.size.getZ();
    }

    private static List<Entity> createEntities(Blueprint blueprint, FakeWorld world) {
        List<Entity> result = new ArrayList<>();
        for (ISchematicEntity schematic : blueprint.entities) {
            if (result.size() >= MAX_ENTITIES) break;
            try {
                CompoundTag serialized = schematic.serializeNBT();
                if (serialized == null || !serialized.contains("entityNbt", Tag.TAG_COMPOUND)) continue;
                CompoundTag tag = serialized.getCompound("entityNbt").copy();
                String id = tag.getString("id");
                if (!id.equals("minecraft:item_frame") && !id.equals("minecraft:glow_item_frame")
                    && !id.equals("minecraft:armor_stand") && !id.equals("minecraft:painting")) continue;
                Vec3 relative = schematic.getPos();
                if (relative == null || !Double.isFinite(relative.x) || !Double.isFinite(relative.y)
                    || !Double.isFinite(relative.z) || relative.x < -1 || relative.y < -1 || relative.z < -1
                    || relative.x > blueprint.size.getX() + 1 || relative.y > blueprint.size.getY() + 1
                    || relative.z > blueprint.size.getZ() + 1) continue;
                BlueprintSecurity.stripUnsafeEntityState(tag);
                Vec3 at = relative.add(Vec3.atLowerCornerOf(FakeWorld.BLUEPRINT_OFFSET));
                tag.put("Pos", NBTUtilBC.writeVec3(at));
                if (!id.equals("minecraft:armor_stand")) {
                    BlockPos anchor = NbtUtils.readBlockPos(serialized.getCompound("hangingPos"))
                        .offset(FakeWorld.BLUEPRINT_OFFSET);
                    Direction facing = NBTUtilBC.readEnum(serialized.get("hangingFacing"), Direction.class);
                    if (facing == null) continue;
                    tag.putInt("TileX", anchor.getX());
                    tag.putInt("TileY", anchor.getY());
                    tag.putInt("TileZ", anchor.getZ());
                    tag.putByte("Facing", (byte) facing.get3DDataValue());
                }
                Entity entity = EntityType.create(tag, world).orElse(null);
                if (entity != null) result.add(entity);
            } catch (RuntimeException | LinkageError ignored) {
                // Optional decorations must not crash the GUI.
            }
        }
        return List.copyOf(result);
    }
}
