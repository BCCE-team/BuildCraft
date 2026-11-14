package buildcraft.energy;

import buildcraft.lib.platform.registry.BCRegistryBinder;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.lib.platform.registry.BCDeferredRegister;
import buildcraft.energy.generation.features.OilGenFeature;
import com.mojang.serialization.MapCodec;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/** Fabric 26.3 oil feature registration, with the 26.3 MapCodec registry. */
public final class BCEnergyWorldGen {
    public static final BCDeferredRegister<MapCodec<? extends Feature>> FEATURE_REGISTER =
        BCDeferredRegister.create("minecraft:worldgen/feature_type", BCEnergy.MODID);
    public static final TagKey<Biome> IS_OIL_BIOME = TagKey.create(
        Registries.BIOME, Identifier.fromNamespaceAndPath(BCEnergy.MODID, "is_oil_biome"));
    public static final BCRegistryEntry<MapCodec<OilGenFeature>> OIL_FEATURE = FEATURE_REGISTER.register(
        "worldgen.feature.oil", () -> OilGenFeature.CODEC);
    public static final ResourceKey<PlacedFeature> OIL_PLACED = ResourceKey.create(
        Registries.PLACED_FEATURE, Identifier.fromNamespaceAndPath(BCEnergy.MODID, "oil_placed_feature"));
    private static boolean worldgenInstalled;

    private BCEnergyWorldGen() {}

    public static synchronized void preInit(BCRegistryBinder binder) {
        FEATURE_REGISTER.register(binder);
        if (worldgenInstalled) return;
        worldgenInstalled = true;
        // Mirrors NeoForge's FLUID_SPRINGS modifier. The feature itself checks
        // API2 worldgen rules, biome/region policy, and per-world configuration.
        BiomeModifications.addFeature(BiomeSelectors.all(),
            GenerationStep.Decoration.FLUID_SPRINGS, OIL_PLACED);
    }
}
