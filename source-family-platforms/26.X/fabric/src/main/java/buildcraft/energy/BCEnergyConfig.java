package buildcraft.energy;

import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.energy.generation.features.OilGenerator;
import buildcraft.energy.generation.features.OilGenerationProfile;
import net.minecraft.resources.Identifier;
import buildcraft.lib.platform.config.BCConfigSpec;
import buildcraft.lib.platform.config.BCConfigSpec.BooleanValue;
import buildcraft.lib.platform.config.BCConfigSpec.ConfigValue;
import buildcraft.lib.platform.config.BCConfigSpec.IntValue;

public class BCEnergyConfig {

    public static BCConfigSpec config;
    private static BCConfigSpec.EnumValue<OilIndustry.Mode> propOilIndustryMode;

    public static boolean enableOilGeneration = true;
    /** Worldgen workers read the selected preset after the config is loaded. */
    public static volatile OilGenerationProfile oilGenerationProfile = OilGenerationProfile.STANDARD;
    public static volatile double customOilGenerationRate = 0.3D;
    /** Published as one value so worldgen workers never observe a mixed profile/rate. */
    public static volatile double oilGenerationMultiplier = 1.0D;
    public static boolean engineExplosionBlockDamage = true;
    public static boolean enableOilSpouts = true;
    public static boolean enableOilBurn = true;
    public static boolean oilIsSticky;
    public static boolean enableStirlingEngineExplosion = false;

    public static int smallSpoutMinHeight = 6;
    public static int smallSpoutMaxHeight = 12;
    public static int largeSpoutMinHeight = 10;
    public static int largeSpoutMaxHeight = 20;

    /** Global hard-deny lists. These always take priority over the matching whitelists. */
    public static final Set<Identifier> biomeBlacklist = new HashSet<>();
    public static final Set<Identifier> dimensionBlacklist = new HashSet<>();

    /** Optional allow lists. An empty biome whitelist allows every biome. */
    public static final Set<Identifier> biomeWhitelist = new HashSet<>();
    public static final Set<Identifier> dimensionWhitelist = new HashSet<>();

    private static boolean biomeWhitelistEnabled;
    private static boolean dimensionWhitelistEnabled;

    public static SpecialEventType christmasEventStatus = SpecialEventType.DAY_ONLY;

    private static BooleanValue propEnableOilGeneration;
    private static BCConfigSpec.EnumValue<OilGenerationProfile> propOilGenerationProfile;
    private static BCConfigSpec.DoubleValue propCustomOilGenerationRate;
    private static BooleanValue propEngineExplosionBlockDamage;
    private static BooleanValue propEnableOilSpouts;
    private static BooleanValue propEnableOilBurn;
    private static BooleanValue propOilIsSticky;
    private static BooleanValue propEnableStirlingEngineExplosion;

    private static IntValue propSmallSpoutMinHeight;
    private static IntValue propSmallSpoutMaxHeight;
    private static IntValue propLargeSpoutMinHeight;
    private static IntValue propLargeSpoutMaxHeight;

    private static ConfigValue<String> propBiomeBlacklist;
    private static ConfigValue<String> propBiomeWhitelist;
    private static ConfigValue<String> propDimensionBlacklist;
    private static ConfigValue<String> propDimensionWhitelist;

    public static void preInit() {
        BCConfigSpec.Builder builder = new BCConfigSpec.Builder();

        builder.push("oilIndustry");
        propOilIndustryMode = builder.comment("Select MODERN fractionation or CLASSIC direct oil-to-fuel refining.",
            "Requires reopening the world. Registered fluids and stored tanks are preserved.")
            .worldRestart().defineEnum("mode", OilIndustry.Mode.MODERN);
        builder.pop();
        builder.push("engines");

        propEnableStirlingEngineExplosion = builder.comment(
            "Whether Stirling Engines explode when they overheat."
        ).define("stirlingExplosion", false);
        propEngineExplosionBlockDamage = builder.comment("Allow overheating engine explosions to damage surrounding blocks.",
            "When disabled, the failed engine is still destroyed and entities can still take damage.")
            .worldRestart().define("engineExplosionBlockDamage", true);

        builder.pop();

        builder.push("worldgen.oil");

        propEnableOilGeneration = builder.comment("Should oil lakes, wells and spouts generate naturally?")
            .worldRestart()
            .define("enable", true);

        propOilGenerationProfile = builder.comment(
            "Oil deposit frequency preset: STANDARD (0.3), HIGH (0.6), VERY_HIGH (1.0), or CUSTOM.",
            "Applies to newly generated chunks; deposit sizes are unchanged.",
            "Custom datapack oilWellGenerationRate values are scaled relative to the STANDARD preset."
        ).worldRestart().defineEnum("generationProfile", OilGenerationProfile.STANDARD,
            OilGenerationProfile.values());
        propCustomOilGenerationRate = builder.comment("Stock-equivalent oil well generation rate in CUSTOM mode.",
            "0.3 is Standard; 0.6 is High; 1.0 is Very High. Zero disables this deposit generator. Enable Oil Generation remains the master switch.")
            .worldRestart().defineInRange("customGenerationRate", 0.3D, 0.0D, 1.0D);

        propEnableOilBurn = builder.comment("Allow flammable oil and oil-product blocks to catch fire. Does not change engine fuel consumption.")
            .define("can_burn", true);

        propOilIsSticky = builder.comment("Slow entities in oil and oil-product blocks marked as sticky.")
            .define("oilIsDense", false);

        propBiomeBlacklist = builder.comment(
            "Comma-separated biome registry names that must never generate oil.",
            "This blacklist has priority over biomeWhitelist and datapack settings."
        ).define("biomeBlacklist", "");

        propBiomeWhitelist = builder.comment(
            "Comma-separated biome registry names that may generate oil.",
            "Leave empty to use the normal biome and datapack generation rules."
        ).define("biomeWhitelist", "");

        propDimensionBlacklist = builder.comment(
            "Comma-separated dimension registry names that must never generate oil.",
            "This blacklist has priority over dimensionWhitelist."
        ).define("dimensionBlacklist", "");

        propDimensionWhitelist = builder.comment(
            "Optional hard allow-list applied after API 2 worldgen rule matching.",
            "Leave empty to let registered BuildCraft worldgen rules decide the dimensions. Blacklist entries always win."
        ).define("dimensionWhitelist", "");

        builder.pop();
        builder.push("worldgen.oil.spouts");

        propEnableOilSpouts = builder.comment(
            "Whether oil spouts are generated. The oil spring below a large deposit is unaffected."
        ).define("enable", true);

        propSmallSpoutMinHeight = builder.comment("The minimum height of a small oil spout.")
            .defineInRange("small_min_height", 6, 0, 256);

        propSmallSpoutMaxHeight = builder.comment("The maximum height of a small oil spout.")
            .defineInRange("small_max_height", 12, 0, 256);

        propLargeSpoutMinHeight = builder.comment("The minimum height of a large oil spout.")
            .defineInRange("large_min_height", 10, 0, 256);

        propLargeSpoutMaxHeight = builder.comment("The maximum height of a large oil spout.")
            .defineInRange("large_max_height", 20, 0, 256);

        builder.pop();
        config = builder.build();
    }

    public static void onReloadConfig(String modId) {
        reloadConfig(modId);
    }

    public static void onLoadConfig(String modId) {
        reloadConfig(modId);
    }

    protected static void reloadConfig(String modId) {
        if (!BCEnergy.MODID.equals(modId)) {
            return;
        }

        OilIndustry.setServerMode(propOilIndustryMode.get());
        enableOilGeneration = propEnableOilGeneration.get();
        oilGenerationProfile = propOilGenerationProfile.get();
        customOilGenerationRate = propCustomOilGenerationRate.get();
        oilGenerationMultiplier = oilGenerationProfile.multiplier(customOilGenerationRate);
        engineExplosionBlockDamage = propEngineExplosionBlockDamage.get();
        enableOilSpouts = propEnableOilSpouts.get();
        enableOilBurn = propEnableOilBurn.get();
        oilIsSticky = propOilIsSticky.get();
        enableStirlingEngineExplosion = propEnableStirlingEngineExplosion.get();

        int configuredSmallMin = propSmallSpoutMinHeight.get();
        int configuredSmallMax = propSmallSpoutMaxHeight.get();
        smallSpoutMinHeight = Math.min(configuredSmallMin, configuredSmallMax);
        smallSpoutMaxHeight = Math.max(configuredSmallMin, configuredSmallMax);

        int configuredLargeMin = propLargeSpoutMinHeight.get();
        int configuredLargeMax = propLargeSpoutMaxHeight.get();
        largeSpoutMinHeight = Math.min(configuredLargeMin, configuredLargeMax);
        largeSpoutMaxHeight = Math.max(configuredLargeMin, configuredLargeMax);

        biomeWhitelistEnabled = parseResourceLocations(propBiomeWhitelist.get(), biomeWhitelist, "biomeWhitelist");
        dimensionWhitelistEnabled = parseResourceLocations(propDimensionWhitelist.get(), dimensionWhitelist, "dimensionWhitelist");
        parseResourceLocations(propBiomeBlacklist.get(), biomeBlacklist, "biomeBlacklist");
        parseResourceLocations(propDimensionBlacklist.get(), dimensionBlacklist, "dimensionBlacklist");

        OilGenerator.clearCache();
        BCEnergy.refreshFlammability();
    }

    public static boolean isBiomeAllowed(Identifier biome) {
        if (biomeBlacklist.contains(biome)) {
            return false;
        }
        return !biomeWhitelistEnabled || biomeWhitelist.contains(biome);
    }

    public static boolean isDimensionAllowed(Identifier dimension) {
        if (dimensionBlacklist.contains(dimension)) {
            return false;
        }
        return !dimensionWhitelistEnabled || dimensionWhitelist.contains(dimension);
    }

    private static boolean parseResourceLocations(String rawValue, Set<Identifier> destination, String optionName) {
        destination.clear();
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return false;
        }

        for (String rawEntry : rawValue.split(",")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            Identifier location = Identifier.tryParse(entry);
            if (location == null) {
                BCLog.logger.warn("Ignoring invalid resource location '{}' in BuildCraft Energy option {}", entry, optionName);
                continue;
            }
            destination.add(location);
        }
        return !destination.isEmpty();
    }

    /** Dynamic biome registries are server-owned in 1.21.1, so validation is performed while parsing IDs. */
    public static void validateBiomeNames() {
    }

    public enum SpecialEventType {
        DISABLED,
        DAY_ONLY,
        MONTH,
        ENABLED;

        public final String lowerCaseName = name().toLowerCase(Locale.ROOT);

        public boolean isEnabled(MonthDay date) {
            if (this == DISABLED) {
                return false;
            }
            if (this == ENABLED) {
                return true;
            }
            LocalDateTime now = LocalDateTime.now();
            if (now.getMonth() != date.getMonth()) {
                return false;
            }
            if (this == MONTH) {
                return true;
            }
            int thisDay = now.getDayOfMonth();
            int wantedDay = date.getDayOfMonth();
            return thisDay >= wantedDay - 1 && thisDay <= wantedDay + 1;
        }
    }
}
