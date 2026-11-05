//? source if ==1.21.11
/* Copyright (c) 2026 BuildCraft Community Edition contributors
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * Render-only snapshot context; adapted from the tested 1.21.1 preview scene.
 */
package buildcraft.builders.client.render.pip;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.builders.BuildersNbtUtil;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.BlueprintSecurity;
import buildcraft.builders.snapshot.FakeWorld;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.transport.block.BlockPipeHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * 1.21.11's PiP renderer cannot use the old 1.21.1 immediate BlockEntity renderer.
 * Instead it extracts native render states from a read-only, neighbouring-block-aware
 * scene and submits them into the SAME offscreen command queue as block models.
 * No server placement, ticking, live world mutation, or buildWithoutChecks is allowed.
 */
final class BlueprintPreviewScene {
    private static WeakReference<Blueprint> lastBlueprint = new WeakReference<>(null);
    private static WeakReference<ClientLevel> lastClient = new WeakReference<>(null);
    private static BlueprintPreviewScene lastScene;
    private static int lastStep;
    private static final int MAX_ENTITIES = 256;
    private static final Set<String> DECORATIVE_ENTITIES = Set.of(
        "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:armor_stand", "minecraft:painting");
    private static final Set<BlockEntityType<?>> FAILED_RENDERERS = new HashSet<>();

    private final PreviewWorld world;
    private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
    private final Map<ISchematicBlock, CompoundTag> tags = new IdentityHashMap<>();
    private final List<Entity> entities;

    static BlueprintPreviewScene forSnapshot(Blueprint snapshot, ClientLevel client, int step) {
        if (lastScene == null || lastBlueprint.get() != snapshot || lastClient.get() != client
            || lastStep != step) {
            lastScene = new BlueprintPreviewScene(snapshot, client, step);
            lastBlueprint = new WeakReference<>(snapshot);
            lastClient = new WeakReference<>(client);
            lastStep = step;
        }
        return lastScene;
    }

    private BlueprintPreviewScene(Blueprint blueprint, ClientLevel client, int step) {
        world = new PreviewWorld(client);
        if (blueprint.data == null || blueprint.size == null) {
            entities = List.of();
            return;
        }
        int total = blueprint.getDataSize();
        Set<Integer> staged = new HashSet<>();
        // Stage every visible BlockEntity (including BC engines, whose entire body
        // is rendered by RenderEngine_BC8) and its immediate neighbours. A double
        // chest must see both halves even when LOD skipped the second cell.
        for (int index = 0; index < total; index += step) {
            BlockState state = stateAt(blueprint, index);
            if (state == null || !state.hasBlockEntity() || isItemOnly(state)) continue;
            BlockPos cell = blueprint.indexToPos(index);
            staged.add(index);
            for (Direction side : Direction.values()) {
                BlockPos neighbour = cell.relative(side);
                if (inside(blueprint, neighbour)) staged.add(blueprint.posToIndex(neighbour));
            }
        }
        // All block states first: neighbor-dependent vanilla renderers (e.g.
        // double chests) need the complete local topology before BE extraction.
        for (int index : staged) {
            BlockState state = stateAt(blueprint, index);
            if (state == null || state.isAir()) continue;
            try {
                world.setBlock(blueprint.indexToPos(index).offset(FakeWorld.BLUEPRINT_OFFSET), state, 0);
            } catch (RuntimeException | LinkageError unsupportedBlock) {
                // One addon must not abort the viewport.
            }
        }
        for (int index : staged) {
            BlockState state = stateAt(blueprint, index);
            if (state == null || !state.hasBlockEntity() || isItemOnly(state)) continue;
            BlockPos local = blueprint.indexToPos(index);
            BlockPos position = local.offset(FakeWorld.BLUEPRINT_OFFSET);
            try {
                BlockEntity entity = world.getBlockEntity(position);
                if (entity == null && state.getBlock() instanceof EntityBlock block) {
                    entity = block.newBlockEntity(position, state);
                }
                if (entity == null) continue;
                entity.setLevel(world);
                blockEntities.put(local.immutable(), entity);
                try {
                    world.setBlockEntity(entity);
                } catch (RuntimeException invalidChunk) {
                    // Keep our independent map for standalone BE rendering.
                }
                ISchematicBlock schematic = cellAt(blueprint, index);
                CompoundTag tag = savedTile(schematic);
                if (tag == null) continue;
                try {
                    CompoundTag safe = BlueprintSecurity.forBlockEntity(state, tag);
                    Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType());
                    if (id != null) safe.putString("id", id.toString());
                    safe.putInt("x", position.getX());
                    safe.putInt("y", position.getY());
                    safe.putInt("z", position.getZ());
                    entity.loadWithComponents(TagValueInput.create(
                        ProblemReporter.DISCARDING, world.registryAccess(), safe));
                } catch (RuntimeException | LinkageError invalidData) {
                    // Use clean defaults instead of a partially decoded/privileged tile.
                    if (state.getBlock() instanceof EntityBlock block) {
                        BlockEntity clean = block.newBlockEntity(position, state);
                        if (clean != null) {
                            clean.setLevel(world);
                            blockEntities.put(local.immutable(), clean);
                            try { world.setBlockEntity(clean); } catch (RuntimeException ignored) {}
                        }
                    }
                }
            } catch (RuntimeException | LinkageError unsupportedTile) {
                // If a BE can't be staged, the PiP renderer uses an item fallback.
            }
        }
        entities = makeEntities(blueprint);
    }

    boolean submitBlockEntity(BlockPos local, PoseStack pose, SubmitNodeStorage storage,
                              CameraRenderState camera) {
        BlockEntity be = blockEntities.get(local);
        if (be == null) return false;
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        BlockEntityRenderer<?, ?> renderer = dispatcher.getRenderer(be);
        if (renderer == null) return false;
        pose.pushPose();
        try {
            // tryExtractRenderState() is for live world chunks: it rejects offscreen
            // objects based on their distance from the *real* player camera. Our
            // staged objects live near FakeWorld.BLUEPRINT_OFFSET, so that call
            // silently culls beds, chests, signs and engines in most actual worlds.
            // Build the renderer's normal state directly, without world-distance culling.
            BlockEntityRenderState state = extractPreviewState(renderer, be);
            state.lightCoords = 15728880;
            pose.translate(local.getX(), local.getY(), local.getZ());
            dispatcher.submit(state, pose, storage, camera);
            return true;
        } catch (RuntimeException | LinkageError rendererFailure) {
            if (FAILED_RENDERERS.add(be.getType())) {
                buildcraft.lib.internal.debug.BCLog.logger.warn(
                    "[builders.preview] BlockEntity renderer failed for " + be.getType(), rendererFailure);
            }
            return false;
        } finally {
            pose.popPose();
        }
    }

    /**
     * Offscreen render states must bypass LevelRenderer's real-world view-range
     * check but still go through the exact vanilla/modded BER implementation.
     * The renderer type is supplied by the dispatcher for this block entity;
     * the raw bridge only erases the associated render-state type parameter.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockEntityRenderState extractPreviewState(BlockEntityRenderer renderer, BlockEntity be) {
        BlockEntityRenderState state = (BlockEntityRenderState) renderer.createRenderState();
        renderer.extractRenderState(be, state, 0.0F,
            Vec3.atCenterOf(be.getBlockPos()), null);
        return state;
    }

    void submitEntities(PoseStack pose, SubmitNodeStorage storage, CameraRenderState camera) {
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        Vec3 offset = Vec3.atLowerCornerOf(FakeWorld.BLUEPRINT_OFFSET);
        for (Entity entity : entities) {
            try {
                EntityRenderState state = dispatcher.extractEntity(entity, 0);
                if (state == null) continue;
                state.lightCoords = 15728880;
                Vec3 relative = entity.position().subtract(offset);
                dispatcher.submit(state, camera, relative.x, relative.y, relative.z, pose, storage);
            } catch (RuntimeException | LinkageError unsupportedEntity) {
                // Bad captured entity/optional renderer affects no other cells.
            }
        }
    }

    private List<Entity> makeEntities(Blueprint blueprint) {
        if (blueprint.entities == null) return List.of();
        List<Entity> result = new ArrayList<>();
        for (ISchematicEntity schematic : blueprint.entities) {
            if (result.size() >= MAX_ENTITIES) break;
            try {
                CompoundTag saved = schematic.serializeNBT();
                if (saved == null || !NbtCompat.contains(saved, "entityNbt", Tag.TAG_COMPOUND)) continue;
                CompoundTag tag = NbtCompat.getCompound(saved, "entityNbt").copy();
                String id = NbtCompat.getString(tag, "id");
                if (!DECORATIVE_ENTITIES.contains(id)) continue;
                Vec3 local = schematic.getPos();
                if (local == null || !Double.isFinite(local.x) || !Double.isFinite(local.y)
                    || !Double.isFinite(local.z) || local.x < 0 || local.y < 0 || local.z < 0
                    || local.x >= blueprint.size.getX() || local.y >= blueprint.size.getY()
                    || local.z >= blueprint.size.getZ()) continue;
                Vec3 position = local.add(Vec3.atLowerCornerOf(FakeWorld.BLUEPRINT_OFFSET));
                tag.put("Pos", NBTUtilBC.writeVec3(position));
                BlueprintSecurity.stripUnsafeEntityState(tag);
                if (id.endsWith("item_frame") || "minecraft:painting".equals(id)) {
                    BlockPos anchor = BuildersNbtUtil.readBlockPos(saved, "hangingPos");
                    if (anchor == null) continue;
                    anchor = anchor.offset(FakeWorld.BLUEPRINT_OFFSET);
                    tag.putIntArray("block_pos", new int[] {anchor.getX(), anchor.getY(), anchor.getZ()});
                    tag.putInt("TileX", anchor.getX());
                    tag.putInt("TileY", anchor.getY());
                    tag.putInt("TileZ", anchor.getZ());
                    Direction facing = NBTUtilBC.readEnum(saved.get("hangingFacing"), Direction.class);
                    if (facing != null) {
                        tag.putByte("Facing", (byte) facing.get3DDataValue());
                        if ("minecraft:painting".equals(id))
                            tag.putByte("facing", (byte) facing.get2DDataValue());
                    }
                }
                // This is an NBT decode ONLY. Never spawn into the live client or
                // call schematic.buildWithoutChecks / level.addFreshEntity.
                Entity entity = EntityType.create(TagValueInput.create(
                    ProblemReporter.DISCARDING, world.registryAccess(), tag), world,
                    EntitySpawnReason.LOAD).orElse(null);
                if (entity != null) result.add(entity);
            } catch (RuntimeException | LinkageError invalidEntity) {
                // Untrusted blueprint entity data must not crash the renderer.
            }
        }
        return List.copyOf(result);
    }

    private CompoundTag savedTile(ISchematicBlock schematic) {
        if (schematic == null) return null;
        if (!tags.containsKey(schematic)) {
            CompoundTag tag = null;
            try {
                CompoundTag written = schematic.serializeNBT();
                if (written != null && NbtCompat.contains(written, "tileNbt", Tag.TAG_COMPOUND))
                    tag = NbtCompat.getCompound(written, "tileNbt").copy();
            } catch (RuntimeException | LinkageError invalidAddon) {}
            tags.put(schematic, tag);
        }
        return tags.get(schematic);
    }

    private static ISchematicBlock cellAt(Blueprint blueprint, int index) {
        if (blueprint.data == null || index < 0 || index >= blueprint.data.length) return null;
        int indexInPalette = blueprint.data[index];
        return indexInPalette >= 0 && indexInPalette < blueprint.palette.size()
            ? blueprint.palette.get(indexInPalette) : null;
    }

    private static BlockState stateAt(Blueprint blueprint, int index) {
        ISchematicBlock block = cellAt(blueprint, index);
        if (block == null) return null;
        try { return block.isAir() ? null : block.getBlockStateForRender(); }
        catch (RuntimeException | LinkageError invalidSchematic) { return null; }
    }

    private static boolean inside(Blueprint blueprint, BlockPos at) {
        return at.getX() >= 0 && at.getX() < blueprint.size.getX()
            && at.getY() >= 0 && at.getY() < blueprint.size.getY()
            && at.getZ() >= 0 && at.getZ() < blueprint.size.getZ();
    }

    private static boolean isItemOnly(BlockState state) {
        // Engines need their BlockEntity: the world renderer owns their entire
        // visible mesh, piston, orientation and texture family. Only pipe holders
        // are reconstructed by the independent pipe-topology preview.
        return state.getBlock() instanceof BlockPipeHolder;
    }

    /** The normal modern FakeWorld is abstract: only these client-context hooks
     * are needed by vanilla BERs and decoration renderers. No world writes escape.
     */
    private static final class PreviewWorld extends FakeWorld {
        PreviewWorld(ClientLevel client) { super(client); }

        // This is a detached, render-only scene. Never let a renderer or addon
        // trigger explosion effects in the real level (or mutate our snapshot).
        // 1.21.11 added blockParticles to Level.explode's abstract signature.
        @Override
        public void explode(Entity source, DamageSource damageSource,
                            ExplosionDamageCalculator damageCalculator,
                            double x, double y, double z, float radius, boolean fire,
                            ExplosionInteraction interaction,
                            ParticleOptions smallParticles, ParticleOptions largeParticles,
                            WeightedList<ExplosionParticleInfo> blockParticles,
                            Holder<SoundEvent> explosionSound) {
            // Intentionally empty: no gameplay simulation or world side effects.
        }

        // In 1.21.11 both overloads use Entity (not Player) as the
        // originating/listening source. FakeWorld still implements the old
        // Player overloads for 1.21.1, so neither of these abstract Level
        // methods was overridden. An offscreen preview must be silent.
        @Override
        public void playSeededSound(Entity source, double x, double y, double z,
                                    Holder<SoundEvent> sound, SoundSource category,
                                    float volume, float pitch, long seed) {
            // No-op: render-only world, no sound playback or network packets.
        }

        @Override
        public void playSeededSound(Entity source, Entity soundEntity,
                                    Holder<SoundEvent> sound, SoundSource category,
                                    float volume, float pitch, long seed) {
            // No-op: render-only world, no sound playback or network packets.
        }

        // LevelAccessor changed its excluded-source argument from Player to
        // Entity in 1.21.11. The inherited FakeWorld(Player,...) overload is
        // not an override and leaves this preview world abstract otherwise.
        // A blueprint preview must never dispatch real level events.
        @Override
        public void levelEvent(Entity source, int eventId, BlockPos pos, int data) {
            // No-op: do not play block-break particles, sounds or world events.
        }

        // LevelReader.getSeaLevel() is abstract in 1.21.11. Use the client
        // dimension's value rather than a hard-coded Overworld sea level.
        // This scene is read-only and must not modify real world data.
        @Override
        public int getSeaLevel() {
            return superLevel.getSeaLevel();
        }

        // CollisionGetter.getWorldBorder() is an abstract interface method
        // in 1.21.11. Keep preview collision boundaries consistent with the
        // client dimension, without constructing or modifying a world border.
        @Override
        public WorldBorder getWorldBorder() {
            return superLevel.getWorldBorder();
        }

        @Override public void setRespawnData(LevelData.RespawnData data) {}
        @Override public LevelData.RespawnData getRespawnData() { return superLevel.getRespawnData(); }
        @Override public Collection<EnderDragonPart> dragonParts() { return List.of(); }
        @Override public EnvironmentAttributeSystem environmentAttributes() {
            return superLevel.environmentAttributes();
        }
    }
}
