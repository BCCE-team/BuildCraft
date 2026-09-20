package buildcraft.energy;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.energy.generation.features.OilFeatureConfiguration;
import buildcraft.energy.generation.features.OilGenFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.Feature;

/** Registers the code-backed oil feature. Biome injection is data-driven through Forge biome modifiers on 1.20.1. */
public final class BCEnergyWorldGen {
    public static final BCDeferredRegister<Feature<?>> FEATURE_REGISTER =
        BCDeferredRegister.create("minecraft:worldgen/feature", BCEnergy.MODID);

    public static final TagKey<Biome> IS_OIL_BIOME = TagKey.create(
        Registries.BIOME, new ResourceLocation(BCEnergy.MODID, "is_oil_biome")
    );

    public static final BCRegistryEntry<Feature<?>> OIL_FEATURE = FEATURE_REGISTER.register(
        "worldgen.feature.oil", () -> new OilGenFeature(OilFeatureConfiguration.CODEC)
    );

    private BCEnergyWorldGen() {
    }

    public static void preInit(BCRegistryBinder modEventBus) {
        BCEnergyBiomeModifiers.register(modEventBus);
        FEATURE_REGISTER.register(modEventBus);
    }
}
