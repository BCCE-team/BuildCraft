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
import net.minecraft.core.Registry;
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
	public boolean place(FeaturePlaceContext<OilFeatureConfiguration> pfc) {
        WorldGenLevel world = pfc.level();
        if (!BCEnergyConfig.enableOilGeneration) {
            return false;
        }

        ResourceDepositRule rule = resolveOilRule(world, contextOrigin(pfc));
        if (rule == null) {
            return false;
        }
        ResourceLocation dimension = world.getLevel().dimension().location();
        if (!BCEnergyConfig.isDimensionAllowed(dimension)) {
            return false;
        }
        double frequency = rule.frequencyMultiplier();
        if (frequency <= 0 || (frequency < 1.0 && pfc.random().nextDouble() >= frequency)) {
            return false;
        }

        BlockPos originPos = pfc.origin();
        ChunkPos chunkPos = new ChunkPos(originPos);
        int chunkX = chunkPos.x;
        int chunkZ = chunkPos.z;

        // OilGenerator still mirrors BC8's short-lived shared cache. Worldgen may run dimensions in parallel, so
        // protect the complete cache/config transaction instead of allowing one worker to replace another's world.
        synchronized (OilGenerator.class) {
            return placeLocked(world, chunkX, chunkZ, pfc.config());
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
                tagId -> dimensionType.is(TagKey.create(Registry.DIMENSION_TYPE_REGISTRY, tagId)),
                tagId -> biomeHolder.is(TagKey.create(Registry.BIOME_REGISTRY, tagId))
            ))
            .sorted(java.util.Comparator.comparingInt(ResourceDepositRule::priority).reversed()
                .thenComparing(rule -> rule.id().toString()))
            .findFirst().orElse(null);
    }

    private boolean placeLocked(WorldGenLevel world, int chunkX, int chunkZ, OilFeatureConfiguration configuration) {
        OilGenerator.config = configuration;

/*        if (world.getLevelType() == LevelType.FLAT) {
            if (DEBUG_OILGEN_BASIC) {
                BCLog.logger.info(
                    "[energy.oilgen] Not generating oil in " + world + " chunk " + chunkX + ", " + chunkZ
                        + " because it's LevelType is FLAT."
                );
            }
            return;
        }*/
//        world.profiler.startSection("bc_oil");
        int count = 0;
        BlockPos min = new BlockPos(chunkX << 4, world.getMinBuildHeight(), chunkZ << 4);
        BlockPos max = new BlockPos((chunkX << 4) + 15, world.getMaxBuildHeight() - 1, (chunkZ << 4) + 15);
        Box box = new Box(min, max);

        for (int cdx = -MAX_CHUNK_RADIUS; cdx <= MAX_CHUNK_RADIUS; cdx++) {
            for (int cdz = -MAX_CHUNK_RADIUS; cdz <= MAX_CHUNK_RADIUS; cdz++) {
                int cx = chunkX + cdx;
                int cz = chunkZ + cdz;
//                world.getProfiler().startSection("scan");
                List<OilStructure> structures = OilGenerator.getStructures(world, cx, cz/*, cdx == 0 && cdz == 0*/);
                OilStructure.Spring spring = null;
//                world.getProfiler().endStartSection("gen");
                for (OilStructure struct : structures) {
                    struct.generate(world, box);
                    if (struct instanceof OilStructure.Spring) {
                        spring = (OilStructure.Spring) struct;
                    }
                }
                if (spring != null && box.contains(spring.pos)) {
                    
                    for (OilStructure struct : structures) {
                        count += struct.countOilBlocks();
                    }
                    spring.generate(world, count);
                }
//                world.getProfiler().pop();;
            }
        }
//        world.getProfiler().pop();
		return count > 0;
    }



}
