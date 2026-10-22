package buildcraft.energy.generation.features;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

public record OilFeatureConfiguration(List<ResourceLocation> excludedBiomes, List<ExcessiveBiome> excessiveBiomes,
        List<ResourceLocation> surfaceDepositBiomes, double oilWellGenerationRate,
        boolean genOilInEveryVanillaBiomes, boolean genOilInEveryModBiomes,
        GenSetting genSetting) {

    public static final MapCodec<OilFeatureConfiguration> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            Codec.list(ResourceLocation.CODEC).fieldOf("excludedBiomes").forGetter(OilFeatureConfiguration::excludedBiomes),
            Codec.list(ExcessiveBiome.CODEC).fieldOf("excessiveBiomes").forGetter(OilFeatureConfiguration::excessiveBiomes),
            Codec.list(ResourceLocation.CODEC).fieldOf("surfaceDepositBiomes").forGetter(OilFeatureConfiguration::surfaceDepositBiomes),
            Codec.doubleRange(0, 100).fieldOf("oilWellGenerationRate").forGetter(OilFeatureConfiguration::oilWellGenerationRate),
            Codec.BOOL.fieldOf("genOilInEveryVanillaBiomes").forGetter(OilFeatureConfiguration::genOilInEveryVanillaBiomes),
            Codec.BOOL.fieldOf("genOilInEveryModBiomes").forGetter(OilFeatureConfiguration::genOilInEveryModBiomes),
            GenSetting.CODEC.fieldOf("oilStructureSetting").forGetter(OilFeatureConfiguration::genSetting)
        ).apply(instance, OilFeatureConfiguration::new)
    );

    public record GenSetting(BlockState oilState, double smallOilGenProb, double mediumOilGenProb,
            double largeOilGenProb) {

        static final Codec<GenSetting> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                BlockState.CODEC.fieldOf("genOilState").forGetter(GenSetting::oilState),
                Codec.doubleRange(0, 100).fieldOf("smallOilGenProb").forGetter(GenSetting::smallOilGenProb),
                Codec.doubleRange(0, 100).fieldOf("mediumOilGenProb").forGetter(GenSetting::mediumOilGenProb),
                Codec.doubleRange(0, 100).fieldOf("largeOilGenProb").forGetter(GenSetting::largeOilGenProb)
            ).apply(instance, GenSetting::new)
        );
    }

    public record ExcessiveBiome(ResourceLocation biome, double noiseScale, double noiseThreshold) {

        static final Codec<ExcessiveBiome> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                ResourceLocation.CODEC.fieldOf("biome").forGetter(ExcessiveBiome::biome),
                Codec.doubleRange(0, 1).fieldOf("noiseScale").forGetter(ExcessiveBiome::noiseScale),
                Codec.doubleRange(0, 1).fieldOf("noiseThreshold").forGetter(ExcessiveBiome::noiseThreshold)
            ).apply(instance, ExcessiveBiome::new)
        );
    }
}
