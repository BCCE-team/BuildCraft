/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import buildcraft.builders.internal.schematic.legacy.ISchematicBlock;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientLevel.ClientLevelData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.minecraft.world.level.entity.Visibility;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEvent.Context;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@SuppressWarnings("NullableProblems")
@OnlyIn(Dist.CLIENT)
public class FakeWorld extends Level {
    @SuppressWarnings("WeakerAccess")
    public static final BlockPos BLUEPRINT_OFFSET = new BlockPos(0, 127, 0);

    public final FakeChunkProvider chunkProvider = new FakeChunkProvider(this);
    protected final List<Entity> entities = new ArrayList<>();

    private final EntityLookup<Entity> entityLookup = new EntityLookup<>();
    private final LongSet tickingChunks = new LongOpenHashSet();
    private final EntitySectionStorage<Entity> sectionStorage = new EntitySectionStorage<>(
        Entity.class,
        sectionPos -> tickingChunks.contains(sectionPos) ? Visibility.TICKING : Visibility.TRACKED
    );
    private final LevelEntityGetter<Entity> entityGetter = new LevelEntityGetterAdapter<>(entityLookup, sectionStorage);

    public final ClientLevel superLevel;

    @SuppressWarnings("WeakerAccess")
    public FakeWorld(ClientLevel level) {
        super(
            new ClientLevelData(Difficulty.EASY, false, false),
            Level.OVERWORLD,
            level.registryAccess(),
            level.dimensionTypeRegistration(),
            level.getProfilerSupplier(),
            true,
            true,
            BiomeManager.obfuscateSeed(0),
            1_000_000
        );
        this.superLevel = level;
    }

    public void clear() {
        chunkProvider.chunks.clear();
        entities.clear();
    }

    @SuppressWarnings("WeakerAccess")
    public void uploadSnapshot(Snapshot snapshot) {
        for (int z = 0; z < snapshot.size.getZ(); z++) {
            for (int y = 0; y < snapshot.size.getY(); y++) {
                for (int x = 0; x < snapshot.size.getX(); x++) {
                    BlockPos pos = new BlockPos(x, y, z).offset(BLUEPRINT_OFFSET);
                    if (snapshot instanceof Blueprint blueprint) {
                        ISchematicBlock schematicBlock = blueprint.palette.get(
                            blueprint.data[snapshot.posToIndex(x, y, z)]
                        );
                        if (!schematicBlock.isAir()) {
                            schematicBlock.buildWithoutChecks(this, pos);
                        }
                    } else if (snapshot instanceof Template template
                        && template.data.get(snapshot.posToIndex(x, y, z))) {
                        setBlock(pos, Blocks.QUARTZ_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
        if (snapshot instanceof Blueprint blueprint) {
            blueprint.entities.forEach(schematicEntity ->
                schematicEntity.buildWithoutChecks(this, BLUEPRINT_OFFSET)
            );
        }
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
    }

    @Override
    public ChunkSource getChunkSource() {
        return chunkProvider;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return true;
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    }

    @Override
    public void gameEvent(GameEvent event, Vec3 pos, Context context) {
    }

    @Override
    public void gameEvent(@Nullable Entity entity, GameEvent event, BlockPos pos) {
    }

    @Override
    public List<? extends Player> players() {
        return ImmutableList.of();
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        return superLevel.getUncachedNoiseBiome(quartX, quartY, quartZ);
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return 0;
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

    @Override
    public void playSeededSound(
        @Nullable Player player,
        double x,
        double y,
        double z,
        Holder<SoundEvent> sound,
        SoundSource source,
        float volume,
        float pitch,
        long seed
    ) {
    }

    @Override
    public void playSeededSound(
        @Nullable Player player,
        Entity entity,
        Holder<SoundEvent> sound,
        SoundSource source,
        float volume,
        float pitch,
        long seed
    ) {
    }

    @Override
    public void playSound(
        @Nullable Player player,
        double x,
        double y,
        double z,
        SoundEvent sound,
        SoundSource source,
        float volume,
        float pitch
    ) {
    }

    @Override
    public void playSound(
        @Nullable Player player,
        Entity entity,
        SoundEvent sound,
        SoundSource source,
        float volume,
        float pitch
    ) {
    }

    @Override
    public String gatherChunkSourceStats() {
        return "FakeChunk";
    }

    @Nullable
    @Override
    public Entity getEntity(int id) {
        return null;
    }

    @Nullable
    @Override
    public MapItemSavedData getMapData(String id) {
        return null;
    }

    @Override
    public void setMapData(String id, MapItemSavedData data) {
    }

    @Override
    public int getFreeMapId() {
        return 0;
    }

    @Override
    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    @Override
    public Scoreboard getScoreboard() {
        return superLevel.getScoreboard();
    }

    @Override
    public RecipeManager getRecipeManager() {
        return superLevel.getRecipeManager();
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return superLevel.enabledFeatures();
    }

    public void addEntity(Entity entity) {
        entities.add(entity);
    }

    public List<Entity> getPreviewEntities() {
        return List.copyOf(entities);
    }

    @Override
    public LevelEntityGetter<Entity> getEntities() {
        return entityGetter;
    }
}
