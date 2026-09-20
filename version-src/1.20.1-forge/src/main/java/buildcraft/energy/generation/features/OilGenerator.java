package buildcraft.energy.generation.features;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;

import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.core.BCCoreBlocks;
import buildcraft.energy.BCEnergyConfig;
import buildcraft.energy.generation.features.OilFeatureConfiguration.ExcessiveBiome;
import buildcraft.energy.generation.features.OilFeatureConfiguration.GenSetting;
import buildcraft.energy.generation.features.OilStructure.GenByPredicate;
import buildcraft.energy.generation.features.OilStructure.ReplaceType;
import buildcraft.lib.delta.SimplexNoise;
import buildcraft.lib.misc.RandUtil;
import buildcraft.lib.misc.VecUtil;
import buildcraft.lib.misc.data.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;

public class OilGenerator {
    /** Random number, used to differentiate generators */
    private static final long MAGIC_GEN_NUMBER = 0xD0_46_B4_E4_0C_7D_07_CFL;

    public static final boolean DEBUG_OILGEN_BASIC = BCDebugging.shouldDebugLog("energy.oilgen");
    public static final boolean DEBUG_OILGEN_ALL = BCDebugging.shouldDebugComplex("energy.oilgen");

    private static final LoadingCache<Long, List<OilStructure>> structureCache
        = CacheBuilder.newBuilder().expireAfterAccess(20, TimeUnit.SECONDS).build(CacheLoader.from(OilGenerator::genCache));

    private static OilFeatureConfiguration config;

    static double xOffset = -1;
    static double zOffset = -1;

    private static WorldGenLevel level;

    public static int worldHeight = -1;//384
    public static int seaLevel = -1;//63
    public static int bottomY = -1;//-64

    private enum GenType {
        LARGE,
        MEDIUM,
        LAKE,
        NONE
    }

    public static void setConfiguration(OilFeatureConfiguration newConfig) {
        if (config != newConfig) {
            config = newConfig;
            structureCache.invalidateAll();
        }
    }

    private static List<OilStructure> genCache(long key){
        return getStructures(level, (int)(key&0xFFFFFFFFL), (int)((key>>32)&0xFFFFFFFFL), false);
    }

    public static List<OilStructure> getStructures(WorldGenLevel world, int cx, int cz) {
        if (level != world) {
            structureCache.invalidateAll();
            level = world;
            Random rand = new Random(world.getSeed());
            int OFFSET_RANGE = 500000;
            OilGenerator.xOffset = rand.nextInt(OFFSET_RANGE) - (OFFSET_RANGE / 2);
            OilGenerator.zOffset = rand.nextInt(OFFSET_RANGE) - (OFFSET_RANGE / 2);
            ServerLevel serverLevel = world.getLevel();
            worldHeight = serverLevel.getHeight();
            seaLevel = serverLevel.getSeaLevel();
            bottomY = serverLevel.getMinBuildHeight();
        }
        return structureCache.getUnchecked((((long) (cz)) << 32) | (cx & 0xFFFFFFFFL));
    }

    public static synchronized void clearCache() {
        structureCache.invalidateAll();
        level = null;
    }

    /*this will not use the cache, only use for testing*/
    protected static List<OilStructure> getStructures(WorldGenLevel world, int cx, int cz, boolean log) {
        if (!BCEnergyConfig.enableOilGeneration || config == null) {
            return ImmutableList.of();
        }
        Random rand = RandUtil.createRandomForChunk(world, cx, cz, MAGIC_GEN_NUMBER);

        // shift to world coordinates
        int x = cx * 16 + 8 + rand.nextInt(16);
        int z = cz * 16 + 8 + rand.nextInt(16);



        // Read the generator's noise biome directly. Asking WorldGenLevel#getBiome for a candidate
        // several chunks away can pull another chunk into generation and form a dependency cycle.
        Holder<Biome> biome = world.getUncachedNoiseBiome(
            QuartPos.fromBlock(x), QuartPos.fromBlock(seaLevel), QuartPos.fromBlock(z)
        );
        Optional<ResourceLocation> biomeKey = biome.unwrapKey().map(resourceKey -> resourceKey.location());
        if (biomeKey.isEmpty()) {
            if (DEBUG_OILGEN_BASIC && log) {
                BCLog.logger.warn("[energy.oilgen] Skipping oil generation in " + toStr(world) + " chunk "
                    + cx + ", " + cz + " because its biome has no registry key");
            }
            return ImmutableList.of();
        }
        ResourceLocation key = biomeKey.get();
        boolean isExcludedBiome = !BCEnergyConfig.isBiomeAllowed(key) || config.excludedBiomes().contains(key);
        if (isExcludedBiome/* == BCEnergyConfig.excludedBiomesIsBlackList*/) {
            if (DEBUG_OILGEN_BASIC & log){//log) {
                BCLog.logger.info(
                    "[energy.oilgen] Not generating oil in " + toStr(world) + " chunk " + cx + ", " + cz
                        + " because the biome we found (" + key.toString() + ") is disabled!"
                );
            }
            return ImmutableList.of();
        }


        boolean oilBiome = config.surfaceDepositBiomes().contains(key);

        double bonus = oilBiome ? 3.0 : 1.0;
        bonus *= config.oilWellGenerationRate();

        Optional<ExcessiveBiome> excessiveBiome = config.excessiveBiomes().stream().filter((a) -> a.biome().equals(key)).findAny();
        if(excessiveBiome.isPresent()){
            ExcessiveBiome excessiveBiome0 = excessiveBiome.get();
            double d0 = SimplexNoise.noise((x + xOffset)*excessiveBiome0.noiseScale(), (z + zOffset)*excessiveBiome0.noiseScale());
            if(d0 > excessiveBiome0.noiseThreshold()) {
                bonus *= 30.0;
                BCLog.d("gen many oil "+ x + ", "+ z);
            }
        }
        GenSetting genSetting = config.genSetting();
        final GenType type;
        if (rand.nextDouble() * 100 <=  genSetting.largeOilGenProb() * bonus) {
            // 0.04%
            type = GenType.LARGE;
        } else if (rand.nextDouble()* 100 <= genSetting.mediumOilGenProb() * bonus) {
            // 0.1%
            type = GenType.MEDIUM;
        } else if (oilBiome && rand.nextDouble()* 100 <= genSetting.smallOilGenProb() * bonus) {
            // 2%
            type = GenType.LAKE;
        } else {
            if (DEBUG_OILGEN_ALL & log) {
                BCLog.logger.info(
                    "[energy.oilgen] Not generating oil in " + toStr(world) + " chunk " + cx + ", " + cz
                        + " because none of the random numbers were above the thresholds for generation"
                );
            }
            return ImmutableList.of();
        }
        if (DEBUG_OILGEN_BASIC && log) {
            BCLog.logger.info(
                "[energy.oilgen] Generating an oil well (" + type.name().toLowerCase(Locale.ROOT)
                    + ") in " + toStr(world) + " chunk " + cx + ", " + cz + " at " + x + ", " + z
            );
        }

        List<OilStructure> structures = new ArrayList<>();
        int lakeRadius;
        int tendrilRadius;
        if (type == GenType.LARGE) {
            lakeRadius = 4;
            tendrilRadius = 25 + rand.nextInt(20);
        } else if (type == GenType.LAKE) {
            lakeRadius = 6;
            tendrilRadius = 25 + rand.nextInt(20);
        } else {
            lakeRadius = 2;
            tendrilRadius = 5 + rand.nextInt(10);
        }
        structures.add(createTendril(new BlockPos(x, seaLevel -1, z), lakeRadius, tendrilRadius, rand));

        if (type != GenType.LAKE) {
            // Generate a spherical cave deposit
            int wellY = bottomY + 20 + rand.nextInt(10);

            int radius;
            if (type == GenType.LARGE) {
                radius = 8 + rand.nextInt(9);
            } else {
                radius = 4 + rand.nextInt(4);
            }

            structures.add(createSphere(new BlockPos(x, wellY, z), radius));

            // Generate a spout
            if (BCEnergyConfig.enableOilSpouts) {
                int maxHeight, minHeight;

                if (type == GenType.LARGE) {
                    minHeight = BCEnergyConfig.largeSpoutMinHeight;
                    maxHeight = BCEnergyConfig.largeSpoutMaxHeight;
                    radius = 1;
                } else {
                    minHeight = BCEnergyConfig.smallSpoutMinHeight;
                    maxHeight = BCEnergyConfig.smallSpoutMaxHeight;
                    radius = 0;
                }
                final int height;
                if (maxHeight == minHeight) {
                    height = maxHeight;
                } else {
                    if (maxHeight < minHeight) {
                        int t = maxHeight;
                        maxHeight = minHeight;
                        minHeight = t;
                    }
                    height = minHeight + rand.nextInt(maxHeight - minHeight + 1);
                }
                structures.add(createSpout(new BlockPos(x, wellY, z), height, radius));
            }

            // Anchor the spring to the configured world bottom and derive tube length from relative Y distance,
            // which keeps the structure inside worlds with negative minimum heights.
            if (type == GenType.LARGE) {
                BlockPos springPos = new BlockPos(x, bottomY, z);
                BlockPos tubeStart = springPos.above();
                int tubeLength = Math.max(0, wellY - tubeStart.getY());
                structures.add(createTube(tubeStart, tubeLength, radius, Axis.Y));
                if (BCCoreBlocks.SPRING.isPresent()) {
                    structures.add(createSpring(springPos));
                }
            }
        }
        return structures;
    }

    private static String toStr(WorldGenLevel world) {
        return world.dimensionType().effectsLocation().toString();
    }

    private static OilStructure createSpout(BlockPos start, int height, int radius) {
        return new OilStructure.Spout(start, ReplaceType.ALWAYS, radius, height);
    }

    public static OilStructure createTubeY(BlockPos base, int height, int radius) {
        return createTube(base, height, radius, Axis.Y);
    }

    public static OilStructure createSpring(BlockPos at) {
        return new OilStructure.Spring(at);
    }

    public static OilStructure createTube(BlockPos center, int length, int radius, Axis axis) {
        int valForAxis = VecUtil.getValue(center, axis);
        BlockPos min = VecUtil.replaceValue(center.offset(-radius, -radius, -radius), axis, valForAxis);
        BlockPos max = VecUtil.replaceValue(center.offset(radius, radius, radius), axis, valForAxis + length);
        double radiusSq = radius * radius;
        int toReplace = valForAxis;
        Predicate<BlockPos> tester = p -> VecUtil.replaceValue(p, axis, toReplace).distSqr(center) <= radiusSq;
        return new GenByPredicate(new Box(min, max), ReplaceType.ALWAYS, tester);
    }

    public static OilStructure createSphere(BlockPos center, int radius) {
        Box box = new Box(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius));
        double radiusSq = radius * radius + 0.01;
        Predicate<BlockPos> tester = p -> p.distSqr(center) <= radiusSq;
        return new GenByPredicate(box, ReplaceType.ALWAYS, tester);
    }

    public static OilStructure createTendril(BlockPos center, int lakeRadius, int radius, Random rand) {
        BlockPos.MutableBlockPos start = center.mutable().move(-radius, 0, -radius);
        int diameter = radius * 2 + 1;
        boolean[][] pattern = new boolean[diameter][diameter];

        int x = radius;
        int z = radius;
        for (int dx = -lakeRadius; dx <= lakeRadius; dx++) {
            for (int dz = -lakeRadius; dz <= lakeRadius; dz++) {
                pattern[x + dx][z + dz] = dx * dx + dz * dz <= lakeRadius * lakeRadius;
            }
        }

        for (int w = 1; w < radius; w++) {
            float proba = (float) (radius - w + 4) / (float) (radius + 4);

            fillPatternIfProba(rand, proba, x, z + w, pattern);
            fillPatternIfProba(rand, proba, x, z - w, pattern);
            fillPatternIfProba(rand, proba, x + w, z, pattern);
            fillPatternIfProba(rand, proba, x - w, z, pattern);

            for (int i = 1; i <= w; i++) {
                fillPatternIfProba(rand, proba, x + i, z + w, pattern);
                fillPatternIfProba(rand, proba, x + i, z - w, pattern);
                fillPatternIfProba(rand, proba, x + w, z + i, pattern);
                fillPatternIfProba(rand, proba, x - w, z + i, pattern);

                fillPatternIfProba(rand, proba, x - i, z + w, pattern);
                fillPatternIfProba(rand, proba, x - i, z - w, pattern);
                fillPatternIfProba(rand, proba, x + w, z - i, pattern);
                fillPatternIfProba(rand, proba, x - w, z - i, pattern);
            }
        }

        int depth = rand.nextDouble() < 0.5 ? 1 : 2;
        return OilStructure.PatternTerrainHeight.create(start, ReplaceType.IS_FOR_LAKE, pattern, depth);
    }

    private static void fillPatternIfProba(Random rand, float proba, int x, int z, boolean[][] pattern) {
        if (rand.nextFloat() <= proba) {
            pattern[x][z] = isSet(pattern, x, z - 1) | isSet(pattern, x, z + 1) //
                | isSet(pattern, x - 1, z) | isSet(pattern, x + 1, z);
        }
    }

    private static boolean isSet(boolean[][] pattern, int x, int z) {
        if (x < 0 || x >= pattern.length) return false;
        if (z < 0 || z >= pattern[x].length) return false;
        return pattern[x][z];
    }

}
