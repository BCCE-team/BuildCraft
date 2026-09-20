//? source if >=1.21.1
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
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.alchemy.PotionBrewing;
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
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

@SuppressWarnings("NullableProblems")
public abstract class FakeWorld extends Level {
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
    private float dayTimeFraction;
    private float dayTimePerTick;

    @SuppressWarnings("WeakerAccess")
    public FakeWorld(ClientLevel level) {
        super(
            new ClientLevelData(Difficulty.EASY, false, false),
            Level.OVERWORLD,
            level.registryAccess(),
            level.dimensionTypeRegistration(),
            true,
            true,
            BiomeManager.obfuscateSeed(0),
            1_000_000
        );
        this.superLevel = level;
        this.dayTimeFraction = level.getDayTimeFraction();
        this.dayTimePerTick = level.getDayTimePerTick();
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
    public BlockEntity getBlockEntity(BlockPos pos) {
        return getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
    }

    public ChunkSource getChunkSource() {
        return chunkProvider;
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        return true;
    }

    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    public void levelEvent(@Nullable Player player, int type, BlockPos pos, int data) {
    }

    public void gameEvent(Holder<GameEvent> event, Vec3 pos, Context context) {
    }

    public List<? extends Player> players() {
        return ImmutableList.of();
    }

    public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        return superLevel.getUncachedNoiseBiome(quartX, quartY, quartZ);
    }

    public float getShade(Direction direction, boolean shade) {
        return 0;
    }

    public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
    }

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

    public void playSound(
        @Nullable Player player,
        Entity entity,
        SoundEvent sound,
        SoundSource source,
        float volume,
        float pitch
    ) {
    }

    public String gatherChunkSourceStats() {
        return "FakeChunk";
    }

    @Nullable
    public Entity getEntity(int id) {
        return null;
    }

    @Nullable
    public MapItemSavedData getMapData(MapId id) {
        return superLevel.getMapData(id);
    }

    public void setMapData(MapId id, MapItemSavedData data) {}


    public MapId getFreeMapId() { return new MapId(0); }


    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
    }

    public Scoreboard getScoreboard() {
        return superLevel.getScoreboard();
    }

    public RecipeManager getRecipeManager() { return null; }


    public FeatureFlagSet enabledFeatures() {
        return superLevel.enabledFeatures();
    }

    public TickRateManager tickRateManager() {
        return superLevel.tickRateManager();
    }

    public net.minecraft.world.item.crafting.RecipeAccess recipeAccess() { return null; }

    public net.minecraft.world.level.block.entity.FuelValues fuelValues() { return null; }

    public PotionBrewing potionBrewing() {
        return superLevel.potionBrewing();
    }

    public void addEntity(Entity entity) {
        entities.add(entity);
    }

    public List<Entity> getPreviewEntities() {
        return List.copyOf(entities);
    }

    public LevelEntityGetter<Entity> getEntities() {
        return entityGetter;
    }

    public void setDayTimeFraction(float dayTimeFraction) {
        this.dayTimeFraction = dayTimeFraction;
    }

    public float getDayTimeFraction() {
        return dayTimeFraction;
    }

    public float getDayTimePerTick() {
        return dayTimePerTick;
    }

    public void setDayTimePerTick(float dayTimePerTick) {
        this.dayTimePerTick = dayTimePerTick;
    }
}
