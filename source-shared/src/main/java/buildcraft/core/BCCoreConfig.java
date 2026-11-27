/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.core;

import buildcraft.lib.internal.mj.MjCapabilities;
import buildcraft.api.v2.config.DisplayUnits;
import buildcraft.api.v2.config.DisplayFormat;
import buildcraft.lib.internal.config.ClientPresentation;
import buildcraft.lib.platform.config.ConfigBinding;
import buildcraft.lib.update.UpdatePreferences;
import buildcraft.api.v2.config.ScreenMode;
import buildcraft.lib.internal.debug.BCLogProfile;
import buildcraft.lib.balance.BalanceConfig;
import buildcraft.lib.internal.mj.MjFeConversion;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.BCLibConfig.ChunkLoaderLevel;
import buildcraft.lib.BCLibConfig.ChunkLoaderType;
import buildcraft.lib.BCLibConfig.RenderRotation;
import buildcraft.lib.BCLibConfig.TimeGap;
import net.minecraft.util.Mth;
import buildcraft.lib.platform.config.BCConfigSpec;
import buildcraft.lib.platform.config.BCConfigSpec.BooleanValue;
import buildcraft.lib.platform.config.BCConfigSpec.DoubleValue;
import buildcraft.lib.platform.config.BCConfigSpec.EnumValue;
import buildcraft.lib.platform.config.BCConfigSpec.IntValue;

public class BCCoreConfig {

    public static BCConfigSpec config;

    public static boolean hidePower;
    public static boolean hideFluid;
    public static boolean pumpsConsumeWater;
    public static int markerMaxDistance;
    public static int pumpMaxDistance;
    public static int networkUpdateRate = 10;
    /** Write a complete server-side robotics decision and movement trace to latest.log. Default off. */
    public static volatile boolean verboseRobotLogging = false;
    public static double miningMultiplier = 1;
    public static int miningMaxDepth;
    /** @deprecated Protection is always enforced by the automation permission checks. */
    @Deprecated public static boolean minePlayerProtected = false;

    private static BooleanValue propColourBlindMode;
    private static BooleanValue propUseColouredLabels;
    private static BooleanValue propUseHighContrastColouredLabels;
    private static BooleanValue propHidePower;
    private static BooleanValue propHideFluid;
    private static BooleanValue propGuideBookEnableDetail;
    private static EnumValue<DisplayUnits.FluidUnit> propFluidVolumeUnit;
    private static BooleanValue propUseLongLocalizedName;
    private static BooleanValue propUseWrenchTag;
    private static BooleanValue propVerboseRobotLogging;
    private static EnumValue<BCLogProfile.Profile> propLoggingProfile;

    private static BCConfigSpec.DoubleValue propMjPerFe;
    private static BCConfigSpec.EnumValue<BCLibConfig.PowerMode> propPowerMode;
    private static IntValue propMaxGuideSearchResults;
    private static IntValue propItemLifespan;
    private static IntValue propNetworkUpdateRate;

    private static EnumValue<TimeGap> propDisplayTimeGap;
    private static EnumValue<ChunkLoaderLevel> propChunkLoadLevel;
    private static EnumValue<ChunkLoaderType> propChunkLoadType;
    private static EnumValue<RenderRotation> propItemRenderRotation;

    public static void registry() {
        BCConfigSpec.Builder builder = new BCConfigSpec.Builder();

        builder.push("display");
        propColourBlindMode = builder.comment("Use color-blind-friendly visuals in supported pipe interfaces.")
            .define("colorBlindMode", false);

        propUseColouredLabels = builder.comment("Should colours be displayed as their own (or a similar) colour in tooltips?")
            .define("useColouredLabels", true);

        propUseHighContrastColouredLabels = builder.comment("Should colours displayed in tooltips use higher-contrast colours?")
            .define("useHighContrastColouredLabels", false);

        propHidePower = builder.comment("Hide supported energy readouts without changing gameplay.")
            .define("hidePowerValues", false);

        propHideFluid = builder.comment("Hide supported fluid readouts without changing gameplay.")
            .define("hideFluidValues", false);

        propGuideBookEnableDetail = builder.comment("Show optional detailed sections of guide pages.").define("guideBookEnableDetail", false);

        propFluidVolumeUnit = builder.comment("Fluid volume unit for all readouts.",
            "BUCKETS displays amounts in B; MILLIBUCKETS displays amounts in mB. Applies to both stored volumes and flow rates.")
            .defineEnum("fluidVolumeUnit", DisplayUnits.DEFAULT.fluid());

        builder.comment("Zone Planner screen mode.",
            "SWITCH remembers the last manually selected layout. LEGACY_ONLY and MODERN_ONLY lock the layout and hide the switch.")
            .defineEnum("zonePlannerMode", ScreenMode.SWITCH);
        builder.comment("Electronic Library screen mode.",
            "SWITCH remembers the last manually selected layout. LEGACY_ONLY and MODERN_ONLY lock the layout and hide the switch.")
            .defineEnum("electronicLibraryMode", ScreenMode.SWITCH);

        propUseLongLocalizedName = builder.comment("Use full unit names for supported fluid readouts and stored MJ values. Energy rates and FE readouts remain compact.")
            .define("useLongLocalizedName", true);

        propDisplayTimeGap = builder.comment(
            "Display unit for all rates.",
            "TICKS displays quantities per game tick; SECONDS displays quantities per nominal 20-tick second. This does not change gameplay."
        ).defineEnum("timeGap", TimeGap.valueOf(DisplayUnits.DEFAULT.rate().name()), TimeGap.values());

        propItemRenderRotation = builder.comment(
            "The rotation that items use when travelling through pipes. Set to 'enabled' for full rotation,",
            "'disabled' for no rotation, or 'horizontals_only' to only rotate items when going horizontally."
        ).defineEnum("itemRenderRotation", RenderRotation.ENABLED, RenderRotation.values());
        builder.comment("Format displayed numbers: plain, grouped with spaces, or abbreviated (K, M, B).")
            .defineEnum("numberFormat", DisplayFormat.DEFAULT.numbers());
        builder.comment("Display engine temperatures in Kelvin, Celsius or Fahrenheit.")
            .defineEnum("temperatureUnit", DisplayFormat.DEFAULT.temperature());
        builder.push("pipes");
        builder.comment("Animate directional fluid textures in pipes.").define("fluidAnimation", true);
        builder.comment("Render all travelling payloads, a single representative, or none.")
            .defineEnum("itemDisplay", ClientPresentation.PipeItems.ALL);
        builder.comment("Maximum distance in blocks from the camera to render pipe contents.")
            .defineInRange("contentRenderDistance", 64, 16, 128);
        builder.comment("Maximum travelling item payloads rendered per pipe in ALL mode.")
            .defineInRange("maxRenderedItems", 64, 1, 512);
        builder.pop();
        builder.push("updates");
        builder.comment("Automatically check for compatible BCCE releases.").define("automaticCheck", true);
        builder.comment("Show available-update notifications on the title screen.").define("notifications", true);
        builder.pop();
        builder.pop();
        // Preserve an existing opt-out from the old update-checker preference file.
        ConfigBinding.legacyClientValue("buildcraftcore:display/updates/automaticCheck", Boolean.toString(UpdatePreferences.legacyEnabled()));

        builder.push("general");
        propChunkLoadType = builder.comment(
            "Controls whether BuildCraft machines may keep chunks loaded.",
            "AUTO enables chunk loading in singleplayer/LAN and disables it on dedicated servers."
        ).worldRestart().defineEnum("chunkLoading", ChunkLoaderType.AUTO, ChunkLoaderType.values());

        propChunkLoadLevel = builder.comment(
            "Selects which BuildCraft chunk-loading tiles are allowed to request tickets."
        ).worldRestart().defineEnum("chunkLoadLevel", ChunkLoaderLevel.SELF_TILES,
                ChunkLoaderLevel.NONE, ChunkLoaderLevel.STRICT_TILES, ChunkLoaderLevel.SELF_TILES);

        propItemLifespan = builder.comment("Seconds before unaccepted machine output dropped by BuildCraft despawns (vanilla normally 300).")
            .defineInRange("itemLifespan", 60, 5, 600);

        propUseWrenchTag = builder.comment(
            "Allow items in the common c:tools/wrench tag to operate BuildCraft wrench interactions.",
            "Legacy items implementing BuildCraft's IToolWrench interface remain supported even when disabled."
        ).define("useWrenchTag", true);

        propNetworkUpdateRate = builder.comment("Base interval in ticks for periodic BuildCraft synchronization. Some trackers add a small delay or send immediate updates.")
            .defineInRange("updateFactor", 10, 1, 10);

        propMaxGuideSearchResults = builder.comment("The maximum number of search results to display in the guide book.")
            .defineInRange("maxGuideSearchResults", 1200, 1, 5000);
        builder.pop();

        builder.push("power");
        propMjPerFe = builder.comment("MJ represented by one FE. The inverse ratio is calculated from the edited value in the configuration screen.")
            .worldRestart().defineInRange("mjPerFe", 0.1D, 0.0001D, 0.2D);
        propPowerMode = builder.comment("MJ_ONLY, MJ_AUTOCONVERT_FE, or DISPLAY_FE")
            .worldRestart().defineEnum("powerMode", BCLibConfig.PowerMode.MJ_ONLY);
        builder.pop();

        builder.push("debug");
        propLoggingProfile = builder.comment(
            "BuildCraft logging level: DEFAULT (errors and important warnings), DEBUG (fallbacks),",
            "HARD_DEBUGGING (machine, task and robot lifecycle events), or VERBOSE (all general diagnostics).",
            "Per-tick robot tracing is controlled separately by verboseRobotLogging."
        ).defineEnum("loggingProfile", BCLogProfile.Profile.DEFAULT, BCLogProfile.Profile.values());
        propVerboseRobotLogging = builder.comment(
            "Write detailed server-side robot state, AI decisions, docking and pathfinding to latest.log every tick.",
            "Very noisy and potentially expensive. Enable only temporarily when diagnosing robotics issues."
        ).define("verboseRobotLogging", false);
        builder.pop();

        config = builder.build();
    }

    private static void updateDisplayUnits() {
        boolean buckets = propFluidVolumeUnit.get() == DisplayUnits.FluidUnit.BUCKETS;
        BCLibConfig.useBucketsStatic = buckets;
        BCLibConfig.useBucketsFlow = buckets;
        BCLibConfig.displayTimeGap = propDisplayTimeGap.get();
    }

    /** Only refresh these live client preferences, not restart-bound gameplay fields. */
    public static void refreshDisplayUnits() {
        boolean previous = BCLibConfig.useBucketsStatic;
        TimeGap previousGap = BCLibConfig.displayTimeGap;
        updateDisplayUnits();
        if (previous != BCLibConfig.useBucketsStatic || previousGap != BCLibConfig.displayTimeGap) BCLibConfig.refreshConfigs();
    }

    public static void onReloadConfig(String modId) {
        reloadConfig(modId);
    }

    public static void onLoadConfig(String modId) {
        reloadConfig(modId);
    }

    protected static void reloadConfig(String modId) {
        if (!BCCore.MODID.equals(modId)) {
            return;
        }

        BCLogProfile.Profile chosenLoggingProfile = propLoggingProfile.get();
        BCLogProfile.applyCoreProfile(chosenLoggingProfile == null
            ? BCLogProfile.Profile.DEFAULT : chosenLoggingProfile);
        verboseRobotLogging = Boolean.TRUE.equals(propVerboseRobotLogging.get());
        BCLibConfig.useColouredLabels = propUseColouredLabels.get();
        BCLibConfig.useHighContrastLabelColours = propUseHighContrastColouredLabels.get();

        hidePower = propHidePower.get();
        hideFluid = propHideFluid.get();
        BCLibConfig.hidePowerValues = hidePower;
        BCLibConfig.hideFluidValues = hideFluid;

        BCLibConfig.guideShowDetail = propGuideBookEnableDetail.get();
        BCLibConfig.maxGuideSearchCount = Mth.clamp(propMaxGuideSearchResults.get(), 1, 5000);
        updateDisplayUnits();
        BCLibConfig.useLongLocalizedName = propUseLongLocalizedName.get();
        BCLibConfig.itemLifespan = propItemLifespan.get();
        pumpsConsumeWater = BalanceConfig.machineInt("buildcraftfactory:pump", "consume_water_sources", 0) != 0;
        BCLibConfig.useWrenchTag = propUseWrenchTag.get();
        markerMaxDistance = BalanceConfig.machineInt("markers", "max_distance_blocks", 64);
        pumpMaxDistance = BalanceConfig.machineInt("buildcraftfactory:pump", "max_distance_blocks", 64);
        networkUpdateRate = Mth.clamp(propNetworkUpdateRate.get(), 1, 10);
        BCLibConfig.colourBlindMode = propColourBlindMode.get();
        BCLibConfig.rotateTravelingItems = propItemRenderRotation.get();
        miningMultiplier = BalanceConfig.machine("mining", "energy_multiplier", 1);
        miningMaxDepth = BalanceConfig.machineInt("mining", "max_depth", 256);
        BCLibConfig.chunkLoadingType = propChunkLoadType.get();
        BCLibConfig.chunkLoadingLevel = propChunkLoadLevel.get();

        BCLibConfig.mjFeConversion = MjFeConversion.createParsed(propMjPerFe.get());
        BCLibConfig.powerMode = propPowerMode.get();
        BCLibConfig.refreshConfigs();
    }
}
