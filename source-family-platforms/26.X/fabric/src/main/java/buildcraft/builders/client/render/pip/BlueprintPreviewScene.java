/* Copyright (c) 2026 BuildCraft Community Edition contributors; MPL-2.0.
 * Detached native render-state extraction for 26.x NeoForge blueprint previews.
 * Do not invoke buildWithoutChecks or write to the real ClientLevel.
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
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.blaze3d.vertex.PoseStack;

import buildcraft.builders.BuildersNbtUtil;
import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import buildcraft.builders.internal.schematic.legacy.ISchematicEntity;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.BlueprintSecurity;
import buildcraft.builders.snapshot.FakeWorld;
import buildcraft.lib.compat.NbtCompat;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.ItemStackUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.clock.ClockManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
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
 * Only a bounded set of snapshot cells and their neighbours is materialized.
 * All entity and block-entity render states are extracted against a local FakeWorld
 * and submitted in the same PiP pass as the captured BlockState geometry.
 */
final class BlueprintPreviewScene {
    private static final int MAX_ENTITIES = 256;
    // Detached client-side preview entities never receive a network-spawn packet.
    // 26.2+ throws from Entity.getId() when its ID is still zero, including
    // during entity render-state extraction. Keep unique, nonzero negative IDs
    // entirely inside the fake preview scene; never register them in a real level.
    private static final AtomicInteger NEXT_PREVIEW_ENTITY_ID = new AtomicInteger(-1);

    private static <T extends Entity> T withPreviewId(T entity) {
        if (entity != null) entity.setId(NEXT_PREVIEW_ENTITY_ID.getAndDecrement());
        return entity;
    }
    private static final Set<String> VISUAL_ENTITIES = Set.of(
        "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting", "minecraft:armor_stand"
    );
    private static final Set<BlockEntityType<?>> FAILED_RENDERERS = new HashSet<>();
    // Report an unsupported entity type/phase only once; never log its NBT.
    private static final Set<String> FAILED_ENTITIES = new HashSet<>();
    private static final boolean TRACE = Boolean.getBoolean("buildcraft.builders.preview.debug");
    private static WeakReference<Blueprint> cachedBlueprint = new WeakReference<>(null);
    private static WeakReference<ClientLevel> cachedClient = new WeakReference<>(null);
    private static BlueprintPreviewScene cachedScene;
    private static int cachedStep;

    private final PreviewWorld world;
    private final Map<BlockPos, BlockEntity> tiles = new HashMap<>();
    private final Map<ISchematicBlock, CompoundTag> savedTags = new IdentityHashMap<>();
    private final List<Entity> entities;

    static BlueprintPreviewScene forSnapshot(Blueprint blueprint, ClientLevel client, int step) {
        if (cachedScene == null || cachedBlueprint.get() != blueprint || cachedClient.get() != client
            || cachedStep != step) {
            cachedScene = new BlueprintPreviewScene(blueprint, client, step);
            cachedBlueprint = new WeakReference<>(blueprint);
            cachedClient = new WeakReference<>(client);
            cachedStep = step;
        }
        return cachedScene;
    }

    private BlueprintPreviewScene(Blueprint blueprint, ClientLevel client, int step) {
        world = new PreviewWorld(client);
        if (blueprint.size == null || blueprint.data == null) {
            entities = List.of();
            return;
        }
        int total = blueprint.getDataSize();
        Set<Integer> staged = new HashSet<>();
        // Chests must see both halves even when LOD samples only one of them.
        for (int i = 0; i < total; i += step) {
            BlockState state = stateAt(blueprint, i);
            if (state == null || !state.hasBlockEntity()) continue;
            BlockPos at = blueprint.indexToPos(i);
            staged.add(i);
            for (Direction facing : Direction.values()) {
                BlockPos adjacent = at.relative(facing);
                if (within(blueprint, adjacent)) staged.add(blueprint.posToIndex(adjacent));
            }
        }
        // Fill block states BEFORE block entities. Vanilla chest/sign renderers
        // consult adjacent blocks and their stored states during extraction.
        for (int i : staged) {
            BlockState state = stateAt(blueprint, i);
            if (state == null || state.isAir()) continue;
            try {
                world.setBlock(blueprint.indexToPos(i).offset(FakeWorld.BLUEPRINT_OFFSET), state, 0);
            } catch (RuntimeException | LinkageError badState) {
                // Optional broken addon cell cannot discard the entire scene.
            }
        }
        for (int i : staged) {
            BlockState state = stateAt(blueprint, i);
            if (state == null || !state.hasBlockEntity()) continue;
            BlockPos local = blueprint.indexToPos(i);
            BlockPos worldPos = local.offset(FakeWorld.BLUEPRINT_OFFSET);
            try {
                BlockEntity blockEntity = world.getBlockEntity(worldPos);
                if (blockEntity == null && state.getBlock() instanceof EntityBlock factory)
                    blockEntity = factory.newBlockEntity(worldPos, state);
                if (blockEntity == null) continue;
                blockEntity.setLevel(world);
                tiles.put(local.immutable(), blockEntity);
                try { world.setBlockEntity(blockEntity); } catch (RuntimeException ignored) {}
                ISchematicBlock cell = cellAt(blueprint, i);
                CompoundTag tag = savedTile(cell);
                if (tag == null) continue;
                try {
                    CompoundTag sanitized = BlueprintSecurity.forBlockEntity(state, tag);
                    Identifier id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                    if (id != null) sanitized.putString("id", id.toString());
                    sanitized.putInt("x", worldPos.getX());
                    sanitized.putInt("y", worldPos.getY());
                    sanitized.putInt("z", worldPos.getZ());
                    blockEntity.loadWithComponents(TagValueInput.create(
                        ProblemReporter.DISCARDING, world.registryAccess(), sanitized));
                } catch (RuntimeException | LinkageError corruptNbt) {
                    // Never leave half-decoded inventory/energy state in preview tiles.
                    if (state.getBlock() instanceof EntityBlock factory) {
                        BlockEntity replacement = factory.newBlockEntity(worldPos, state);
                        if (replacement != null) {
                            replacement.setLevel(world);
                            tiles.put(local.immutable(), replacement);
                            try { world.setBlockEntity(replacement); } catch (RuntimeException ignored) {}
                        }
                    }
                }
            } catch (RuntimeException | LinkageError invalidAddonTile) {
                // Leave an item-model fallback to the caller.
            }
        }
        entities = decodeEntities(blueprint);
    }

    /** Extract directly, bypassing the real camera distance/frustum rejection. */
    boolean submitBlockEntity(BlockPos local, PoseStack pose, SubmitNodeCollector sink,
                              CameraRenderState camera) {
        BlockEntity tile = tiles.get(local);
        if (tile == null) return false;
        try {
            BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
            BlockEntityRenderer<BlockEntity, BlockEntityRenderState> renderer = dispatcher.getRenderer(tile);
            if (renderer == null) return false;
            BlockEntityRenderState state = renderer.createRenderState();
            renderer.extractRenderState(tile, state, 0.0F, Vec3.atCenterOf(tile.getBlockPos()), null);
            state.lightCoords = 15728880;
            pose.pushPose();
            try {
                pose.translate(local.getX(), local.getY(), local.getZ());
                dispatcher.submit(state, pose, sink, camera);
            } finally {
                pose.popPose();
            }
            return true;
        } catch (RuntimeException | LinkageError rendererFailure) {
            if (FAILED_RENDERERS.add(tile.getType())) {
                buildcraft.lib.internal.debug.BCLog.logger.warn(
                    "[builders.preview] 26.x BlockEntity renderer failed for " + tile.getType(), rendererFailure);
            }
            return false;
        }
    }

    void submitEntities(PoseStack pose, SubmitNodeCollector sink, CameraRenderState camera) {
        EntityRenderDispatcher renderer = Minecraft.getInstance().getEntityRenderDispatcher();
        Vec3 offset = Vec3.atLowerCornerOf(FakeWorld.BLUEPRINT_OFFSET);
        for (Entity entity : entities) {
            try {
                EntityRenderState state = renderer.extractEntity(entity, 0.0F);
                if (state == null) continue;
                state.lightCoords = 15728880;
                Vec3 local = entity.position().subtract(offset);
                renderer.submit(state, camera, local.x, local.y, local.z, pose, sink);
            } catch (RuntimeException | LinkageError invalidEntity) {
                // A renderer error used to be swallowed silently, making every
                // decorative entity disappear with no way to locate the cause.
                String type = EntityType.getKey(entity.getType()).toString();
                reportEntityFailure("submit", type, invalidEntity);
            }
        }
    }

    private List<Entity> decodeEntities(Blueprint blueprint) {
        if (blueprint.entities == null) return List.of();
        List<Entity> output = new ArrayList<>();
        int candidates = 0;
        int skipped = 0;
        for (ISchematicEntity schematic : blueprint.entities) {
            if (output.size() >= MAX_ENTITIES) break;
            String id = "<unreadable>";
            try {
                CompoundTag saved = schematic.serializeNBT();
                if (saved == null || !NbtCompat.contains(saved, "entityNbt", Tag.TAG_COMPOUND)) continue;
                CompoundTag tag = NbtCompat.getCompound(saved, "entityNbt").copy();
                id = NbtCompat.getString(tag, "id");
                if (!VISUAL_ENTITIES.contains(id)) continue;
                candidates++;
                Vec3 local = schematic.getPos();
                if (local == null || !Double.isFinite(local.x) || !Double.isFinite(local.y)
                    || !Double.isFinite(local.z)) continue;
                boolean hanging = id.endsWith("item_frame") || id.equals("minecraft:painting");
                if (hanging) {
                    // The frame centre may be up to half a block OUTSIDE a scan
                    // boundary even while its mounting block is inside the blueprint.
                    BlockPos attachment = BuildersNbtUtil.readBlockPos(saved, "hangingPos");
                    if (attachment == null || !within(blueprint, attachment)) continue;
                    // Bound untrusted floating-point coordinates relative to the
                    // verified mount. Large paintings may extend past its cell,
                    // but a forged distant position must not reach the renderer.
                    if (Math.abs(local.x - attachment.getX()) > 4.0D
                        || Math.abs(local.y - attachment.getY()) > 4.0D
                        || Math.abs(local.z - attachment.getZ()) > 4.0D) continue;
                    BlockPos anchor = attachment.offset(FakeWorld.BLUEPRINT_OFFSET);
                    tag.putIntArray("block_pos", new int[] {anchor.getX(), anchor.getY(), anchor.getZ()});
                    tag.putInt("TileX", anchor.getX());
                    tag.putInt("TileY", anchor.getY());
                    tag.putInt("TileZ", anchor.getZ());
                    Direction facing = NBTUtilBC.readEnum(saved.get("hangingFacing"), Direction.class);
                    if (facing == null) continue;
                    if (id.equals("minecraft:painting"))
                        tag.putByte("facing", (byte) facing.get2DDataValue());
                    else
                        tag.putByte("Facing", (byte) facing.get3DDataValue());
                } else if (local.x < 0 || local.y < 0 || local.z < 0
                    || local.x >= blueprint.size.getX() || local.y >= blueprint.size.getY()
                    || local.z >= blueprint.size.getZ()) {
                    continue;
                }
                Vec3 position = local.add(Vec3.atLowerCornerOf(FakeWorld.BLUEPRINT_OFFSET));
                tag.put("Pos", NBTUtilBC.writeVec3(position));
                BlueprintSecurity.stripUnsafeEntityState(tag);
                // 26.2 changes untyped entity loading; construct display types
                // explicitly rather than depending on the captured NBT discriminator.
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id)).orElse(null);
                Entity entity = null;
                if (hanging && (id.equals("minecraft:item_frame") || id.equals("minecraft:glow_item_frame"))) {
                    // Do not snapTo() a hanging frame. Since 26.2 its render
                    // origin and bounding box are derived from its attachment
                    // and facing; a saved floating-point Pos is NOT its anchor.
                    // Construct the actual vanilla frame with the captured
                    // attachment and copy the framed item and item rotation.
                    BlockPos anchor = BuildersNbtUtil.readBlockPos(saved, "hangingPos")
                        .offset(FakeWorld.BLUEPRINT_OFFSET);
                    Direction facing = NBTUtilBC.readEnum(saved.get("hangingFacing"), Direction.class);
                    ItemFrame frame = withPreviewId(id.equals("minecraft:glow_item_frame")
                        ? new GlowItemFrame(world, anchor, facing)
                        : new ItemFrame(world, anchor, facing));
                    try {
                        ItemStack item = ItemStackUtil.parseOptional(world.registryAccess(),
                            NbtCompat.getCompound(tag, "Item"));
                        if (!item.isEmpty()) frame.setItem(item);
                        frame.setRotation(NbtCompat.getInt(tag, "ItemRotation"));
                    } catch (RuntimeException invalidFramedItem) {
                        reportEntityFailure("item", id, invalidFramedItem);
                    }
                    entity = frame;
                }
                if (id.equals("minecraft:armor_stand")) {
                    // 26.2's untyped load path is no longer a safe way to
                    // reconstruct display-only armor stands in the detached
                    // scene. The world/position constructor bypasses spawn
                    // placement checks; saved pose and gear are restored below.
                    ArmorStand stand = withPreviewId(new ArmorStand(world, position.x, position.y, position.z));
                    try {
                        stand.load(TagValueInput.create(
                            ProblemReporter.DISCARDING, world.registryAccess(), tag));
                    } catch (RuntimeException invalidSavedData) {
                        reportEntityFailure("load", id, invalidSavedData);
                    }
                    entity = stand;
                }
                if (entity == null && type != null) {
                    // Paintings use their saved variant and hanging metadata;
                    // keep the NBT loader for those types. Avoid depending on
                    // it for item frames and armor stands constructed above.
                    try {
                        entity = withPreviewId(EntityType.create(type,
                            TagValueInput.create(ProblemReporter.DISCARDING, world.registryAccess(), tag),
                            world, EntitySpawnReason.LOAD).orElse(null));
                    } catch (RuntimeException invalidSavedData) {
                        reportEntityFailure("decode", id, invalidSavedData);
                    }
                }
                if (entity == null && type != null) {
                    // Last-resort display instance if the saved type rejects
                    // an otherwise valid legacy blueprint. Never spawn it.
                    entity = withPreviewId(type.create(world, EntitySpawnReason.LOAD));
                    if (entity != null) {
                        try {
                            entity.load(TagValueInput.create(
                                ProblemReporter.DISCARDING, world.registryAccess(), tag));
                        } catch (RuntimeException invalidSavedData) {
                            reportEntityFailure("load", id, invalidSavedData);
                        }
                    }
                }
                if (entity != null) {
                    // When 26.2's load codec leaves a newly constructed display
                    // entity at its default world origin, it is invisible inside
                    // the offscreen blueprint (its fake world is offset by Y=127).
                    // Force the captured local position; keep the loaded direction
                    // and rotation, including the anchor for hanging decorations.
                    if (!hanging && entity.position().distanceToSqr(position) > 0.01D) {
                        entity.snapTo(position.x, position.y, position.z,
                            entity.getYRot(), entity.getXRot());
                    }
                    restorePreviewEquipment(entity, tag);
                    output.add(entity);
                } else {
                    skipped++;
                    reportEntityFailure("decode", id, null);
                }
            } catch (RuntimeException | LinkageError invalidEntity) {
                skipped++;
                reportEntityFailure("prepare", id, invalidEntity);
            }
        }
        if (TRACE && (!blueprint.entities.isEmpty() || candidates > 0)) {
            buildcraft.lib.internal.debug.BCLog.logger.info(
                "[builders.preview] display entities saved=" + blueprint.entities.size()
                + " recognised=" + candidates + " decoded=" + output.size()
                + " rejected=" + skipped);
        }
        return List.copyOf(output);
    }

    private static void reportEntityFailure(String phase, String type, Throwable error) {
        if (!FAILED_ENTITIES.add(phase + ":" + type)) return;
        if (error == null) {
            buildcraft.lib.internal.debug.BCLog.logger.warn(
                "[builders.preview] Cannot " + phase + " decorative entity " + type);
        } else {
            buildcraft.lib.internal.debug.BCLog.logger.warn(
                "[builders.preview] Failed to " + phase + " decorative entity " + type, error);
        }
    }

    // BCCE stores armour-stand gear under the charged HandItems/ArmorItems
    // blueprint schema. 26.x Minecraft expects its own `equipment` structure;
    // restoring only the whitelisted preview instance preserves visible gear.
    private void restorePreviewEquipment(Entity entity, CompoundTag nbt) {
        if (!(entity instanceof ArmorStand stand)) return;
        restoreSlots(stand, nbt, "HandItems",
            new EquipmentSlot[] {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND});
        restoreSlots(stand, nbt, "ArmorItems",
            new EquipmentSlot[] {EquipmentSlot.FEET, EquipmentSlot.LEGS,
                EquipmentSlot.CHEST, EquipmentSlot.HEAD});
    }

    private void restoreSlots(ArmorStand stand, CompoundTag nbt, String key, EquipmentSlot[] slots) {
        ListTag data = NbtCompat.getList(nbt, key);
        for (int i = 0; i < slots.length; i++) {
            CompoundTag saved = i < data.size() ? NbtCompat.getCompound(data, i) : new CompoundTag();
            try {
                ItemStack item = ItemStackUtil.parseOptional(world.registryAccess(), saved);
                stand.setItemSlot(slots[i], item);
            } catch (RuntimeException invalidStack) {
                // A malformed display item must not prevent the stand itself.
            }
        }
    }

    private CompoundTag savedTile(ISchematicBlock schematic) {
        if (schematic == null) return null;
        if (!savedTags.containsKey(schematic)) {
            CompoundTag tag = null;
            try {
                CompoundTag serialized = schematic.serializeNBT();
                if (serialized != null && NbtCompat.contains(serialized, "tileNbt", Tag.TAG_COMPOUND))
                    tag = NbtCompat.getCompound(serialized, "tileNbt").copy();
            } catch (RuntimeException | LinkageError invalidCodec) {}
            savedTags.put(schematic, tag);
        }
        return savedTags.get(schematic);
    }

    private static ISchematicBlock cellAt(Blueprint blueprint, int i) {
        if (i < 0 || blueprint.data == null || i >= blueprint.data.length) return null;
        int palette = blueprint.data[i];
        return palette >= 0 && palette < blueprint.palette.size() ? blueprint.palette.get(palette) : null;
    }

    private static BlockState stateAt(Blueprint blueprint, int i) {
        ISchematicBlock schematic = cellAt(blueprint, i);
        if (schematic == null || schematic.isAir()) return null;
        try { return schematic.getBlockStateForRender(); }
        catch (RuntimeException | LinkageError invalidCodec) { return null; }
    }

    private static boolean within(Blueprint blueprint, BlockPos pos) {
        return pos.getX() >= 0 && pos.getY() >= 0 && pos.getZ() >= 0
            && pos.getX() < blueprint.size.getX()
            && pos.getY() < blueprint.size.getY()
            && pos.getZ() < blueprint.size.getZ();
    }

    /** Concrete read-only adapter for 26.1.2, 26.2 and 26.3's Level contracts. */
    private static final class PreviewWorld extends FakeWorld {
        PreviewWorld(ClientLevel client) { super(client); }

        @Override
        public void explode(Entity source, DamageSource damageSource,
                            ExplosionDamageCalculator damageCalculator,
                            double x, double y, double z, float radius, boolean fire,
                            ExplosionInteraction interaction, ParticleOptions smallParticles,
                            ParticleOptions largeParticles,
                            WeightedList<ExplosionParticleInfo> blockParticles,
                            Holder<SoundEvent> sound) {
            // No damage, effects, drops or network side-effects.
        }

        @Override public void playSeededSound(Entity source, double x, double y, double z,
                                              Holder<SoundEvent> sound, SoundSource category,
                                              float volume, float pitch, long seed) {}
        @Override public void playSeededSound(Entity source, Entity soundEntity,
                                              Holder<SoundEvent> sound, SoundSource category,
                                              float volume, float pitch, long seed) {}
        @Override public void levelEvent(Entity source, int event, BlockPos pos, int data) {}
        @Override public int getSeaLevel() { return superLevel.getSeaLevel(); }
        @Override public WorldBorder getWorldBorder() { return superLevel.getWorldBorder(); }
        @Override public ClockManager clockManager() { return superLevel.clockManager(); }
        @Override public EnvironmentAttributeSystem environmentAttributes() {
            return superLevel.environmentAttributes();
        }
        @Override public void setRespawnData(LevelData.RespawnData data) {}
        @Override public LevelData.RespawnData getRespawnData() { return superLevel.getRespawnData(); }
    }
}
