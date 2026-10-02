package buildcraft.energy.generation.features;

import java.util.List;

import com.mojang.serialization.Codec;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.content.BuildCraftContentIds;
import buildcraft.api.v2.worldgen.ResourceDepositRule;
import buildcraft.api.v2.worldgen.WorldgenService;
import buildcraft.energy.BCEnergyConfig;
import buildcraft.lib.misc.data.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

public class OilGenFeature extends Feature<OilFeatureConfiguration>{

    /** The distance that oil generation will be checked to see if their structures overlap with the currently
     * generating chunk. This should be large enough that all oil generation can fit inside this radius. If this number
     * is too big then oil generation will be slightly slower */
    private static final int MAX_CHUNK_RADIUS = 5;


    public OilGenFeature(Codec<OilFeatureConfiguration> p_65786_) {
        super(p_65786_);
    }

    @Override
    public boolean place(FeaturePlaceContext<OilFeatureConfiguration> context) {
        WorldGenLevel world = context.level();
        if (!BCEnergyConfig.enableOilGeneration) {
            return false;
        }

        ResourceDepositRule rule = resolveOilRule(world, contextOrigin(context));
        if (rule == null) {
            return false;
        }
        ResourceLocation dimension = world.getLevel().dimension().location();
        if (!BCEnergyConfig.isDimensionAllowed(dimension)) {
            return false;
        }
        double frequency = rule.frequencyMultiplier();
        if (frequency <= 0 || (frequency < 1.0 && context.random().nextDouble() >= frequency)) {
            return false;
        }

        ChunkPos chunkPos = new ChunkPos(context.origin().getX() >> 4, context.origin().getZ() >> 4);
        // OilGenerator uses a short-lived shared cache. Worldgen may run dimensions in parallel,
        // so protect the complete world/config/cache transaction.
        synchronized (OilGenerator.class) {
            return placeLocked(world, chunkPos.x(), chunkPos.z(), context.config());
        }
    }


    private static BlockPos contextOrigin(FeaturePlaceContext<OilFeatureConfiguration> context) {
        return context.origin();
    }

    private static ResourceDepositRule resolveOilRule(WorldGenLevel world, BlockPos origin) {
        ResourceLocation dimension = world.getLevel().dimension().location();
        var biomeHolder = world.getBiome(origin);
        ResourceLocation biome = biomeHolder.unwrapKey().map(ResourceKey::location).orElse(null);
        if (biome == null) return null;

        var dimensionType = world.getLevel().dimensionTypeRegistration();
        WorldgenService service = BuildCraftApi.service(BuildCraftServices.WORLDGEN);
        return service.rules().stream()
            .filter(ResourceDepositRule::enabled)
            .filter(rule -> rule.profile().equals(BuildCraftContentIds.Worldgen.STANDARD_OIL))
            .filter(rule -> rule.target().matches(
                dimension, biome,
                tagId -> dimensionType.is(TagKey.create(Registries.DIMENSION_TYPE, tagId)),
                tagId -> biomeHolder.is(TagKey.create(Registries.BIOME, tagId))
            ))
            .sorted(java.util.Comparator.comparingInt(ResourceDepositRule::priority).reversed()
                .thenComparing(rule -> rule.id().toString()))
            .findFirst().orElse(null);
    }

    private boolean placeLocked(WorldGenLevel world, int chunkX, int chunkZ,
            OilFeatureConfiguration configuration) {
        OilGenerator.setConfiguration(configuration);
        int count = 0;
        BlockPos min = new BlockPos(chunkX << 4, world.getMinBuildHeight(), chunkZ << 4);
        BlockPos max = new BlockPos((chunkX << 4) + 15, world.getMaxBuildHeight() - 1,
                (chunkZ << 4) + 15);
        Box box = new Box(min, max);

        for (int cdx = -MAX_CHUNK_RADIUS; cdx <= MAX_CHUNK_RADIUS; cdx++) {
            for (int cdz = -MAX_CHUNK_RADIUS; cdz <= MAX_CHUNK_RADIUS; cdz++) {
                List<OilStructure> structures = OilGenerator.getStructures(world, chunkX + cdx, chunkZ + cdz);
                OilStructure.Spring spring = null;
                for (OilStructure structure : structures) {
                    structure.generate(world, box);
                    if (structure instanceof OilStructure.Spring foundSpring) {
                        spring = foundSpring;
                    }
                }
                if (spring != null && box.contains(spring.pos)) {
                    for (OilStructure structure : structures) {
                        count += structure.countOilBlocks();
                    }
                    spring.generate(world, count);
                }
            }
        }
        return count > 0;
    }


}
