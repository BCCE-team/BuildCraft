package buildcraft.energy;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep.Decoration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo.BiomeInfo;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Platform bridge which installs the BuildCraft oil placed feature into every biome.
 *
 * <p>The feature itself performs the authoritative API 2 world/dimension/biome rule check at placement time.
 * Installing it broadly here is what allows an addon to opt a custom dimension into oil generation through
 * {@code WorldgenService} without also having to patch loader-specific biome modifier data.</p>
 */
public final class BCEnergyBiomeModifiers {
    private static final BCDeferredRegister<MapCodec<? extends BiomeModifier>> SERIALIZERS =
        BCDeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, BCEnergy.MODID);

    public static final BCRegistryEntry<MapCodec<OilFeatureInjectionModifier>> OIL_FEATURE_INJECTION =
        SERIALIZERS.register("oil_feature_injection", () -> RecordCodecBuilder.mapCodec(builder -> builder.group(
            PlacedFeature.LIST_CODEC.fieldOf("features").forGetter(OilFeatureInjectionModifier::features)
        ).apply(builder, OilFeatureInjectionModifier::new)));

    private BCEnergyBiomeModifiers() {
    }

    public static void register(BCRegistryBinder modEventBus) {
        SERIALIZERS.register(modEventBus);
    }

    public record OilFeatureInjectionModifier(HolderSet<PlacedFeature> features) implements BiomeModifier {
        @Override
        public void modify(Holder<Biome> biome, Phase phase, BiomeInfo.Builder builder) {
            if (phase != Phase.ADD) {
                return;
            }
            features.forEach(feature -> builder.getGenerationSettings().addFeature(Decoration.FLUID_SPRINGS, feature));
        }

        @Override
        public MapCodec<? extends BiomeModifier> codec() {
            return OIL_FEATURE_INJECTION.get();
        }
    }
}
