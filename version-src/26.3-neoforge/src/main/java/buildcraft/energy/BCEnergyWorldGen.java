package buildcraft.energy;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.energy.generation.features.OilGenFeature;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.Feature;
import com.mojang.serialization.MapCodec;

/** Registers the code-backed oil feature. Biome injection is data-driven through NeoForge biome modifiers on 1.21.1. */
public final class BCEnergyWorldGen {
    public static final BCDeferredRegister<MapCodec<? extends Feature>> FEATURE_REGISTER =
        BCDeferredRegister.create("minecraft:worldgen/feature_type", BCEnergy.MODID);

    public static final TagKey<Biome> IS_OIL_BIOME = TagKey.create(
        Registries.BIOME, ResourceLocation.fromNamespaceAndPath(BCEnergy.MODID, "is_oil_biome")
    );

    public static final BCRegistryEntry<MapCodec<OilGenFeature>> OIL_FEATURE = FEATURE_REGISTER.register(
        "worldgen.feature.oil", () -> OilGenFeature.CODEC
    );

    private BCEnergyWorldGen() {
    }

    public static void preInit(BCRegistryBinder modEventBus) {
        BCEnergyBiomeModifiers.register(modEventBus);
        FEATURE_REGISTER.register(modEventBus);
    }
}
