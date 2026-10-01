package buildcraft.energy;

import java.time.LocalDateTime;
import java.time.MonthDay;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.energy.generation.features.OilGenerator;
import net.minecraft.resources.ResourceLocation;
import buildcraft.lib.platform.config.BCConfigSpec;
import buildcraft.lib.platform.config.BCConfigSpec.BooleanValue;
import buildcraft.lib.platform.config.BCConfigSpec.ConfigValue;
import buildcraft.lib.platform.config.BCConfigSpec.IntValue;

public class BCEnergyConfig {

    public static BCConfigSpec config;

    public static boolean enableOilGeneration = true;
    public static boolean enableOilSpouts = true;
    public static boolean enableOilBurn = true;
    public static boolean oilIsSticky;
    public static boolean enableStirlingEngineExplosion = false;

    public static int smallSpoutMinHeight = 6;
    public static int smallSpoutMaxHeight = 12;
    public static int largeSpoutMinHeight = 10;
    public static int largeSpoutMaxHeight = 20;

    /** Global hard-deny lists. These always take priority over the matching whitelists. */
    public static final Set<ResourceLocation> biomeBlacklist = new HashSet<>();
    public static final Set<ResourceLocation> dimensionBlacklist = new HashSet<>();

    /** Optional allow lists. An empty biome whitelist allows every biome. */
    public static final Set<ResourceLocation> biomeWhitelist = new HashSet<>();
    public static final Set<ResourceLocation> dimensionWhitelist = new HashSet<>();

    private static boolean biomeWhitelistEnabled;
    private static boolean dimensionWhitelistEnabled;

    public static SpecialEventType christmasEventStatus = SpecialEventType.DAY_ONLY;

    private static BooleanValue propEnableOilGeneration;
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

        builder.push("engines");

        propEnableStirlingEngineExplosion = builder.comment(
            "Whether Stirling Engines explode when they overheat."
        ).define("stirlingExplosion", false);

        builder.pop();

        builder.push("worldgen.oil");

        propEnableOilGeneration = builder.comment("Should oil lakes, wells and spouts generate naturally?")
            .worldRestart()
            .define("enable", true);

        propEnableOilBurn = builder.comment("Can oil blocks burn?")
            .define("can_burn", true);

        propOilIsSticky = builder.comment("Should oil slow entities moving through it?")
            .define("oilIsDense", false);

        propBiomeBlacklist = builder.comment(
            "Comma-separated biome registry names that must never generate oil.",
            "This blacklist has priority over biomeWhitelist and datapack settings."
        ).define("biomeBlacklist", "");

        propBiomeWhitelist = builder.comment(
            "Comma-separated biome registry names that may generate oil.",
            "Leave empty to allow every biome that is not blacklisted."
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

        enableOilGeneration = propEnableOilGeneration.get();
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
    }

    public static boolean isBiomeAllowed(ResourceLocation biome) {
        if (biomeBlacklist.contains(biome)) {
            return false;
        }
        return !biomeWhitelistEnabled || biomeWhitelist.contains(biome);
    }

    public static boolean isDimensionAllowed(ResourceLocation dimension) {
        if (dimensionBlacklist.contains(dimension)) {
            return false;
        }
        return !dimensionWhitelistEnabled || dimensionWhitelist.contains(dimension);
    }

    private static boolean parseResourceLocations(String rawValue, Set<ResourceLocation> destination, String optionName) {
        destination.clear();
        if (rawValue == null || rawValue.trim().isEmpty()) {
            return false;
        }

        for (String rawEntry : rawValue.split(",")) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            ResourceLocation location = ResourceLocation.tryParse(entry);
            if (location == null) {
                BCLog.logger.warn("Ignoring invalid resource location '{}' in BuildCraft Energy option {}", entry, optionName);
                continue;
            }
            destination.add(location);
        }
        return !destination.isEmpty();
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
