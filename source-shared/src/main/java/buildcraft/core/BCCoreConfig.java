/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.core;

import buildcraft.lib.internal.mj.MjCapabilities;
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

    public static boolean minePlayerProtected;
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

    private static BooleanValue propColourBlindMode;
    private static BooleanValue propUseColouredLabels;
    private static BooleanValue propUseHighContrastColouredLabels;
    private static BooleanValue propHidePower;
    private static BooleanValue propHideFluid;
    private static BooleanValue propGuideBookEnableDetail;
    private static BooleanValue propUseBucketsStatic;
    private static BooleanValue propUseBucketsFlow;
    private static BooleanValue propUseLongLocalizedName;
    private static BooleanValue propUseSwappableSprites;
    private static BooleanValue propMinePlayerProtected;
    private static BooleanValue propEnableAnimatedSprites;
    private static BooleanValue propUseWrenchTag;
    private static BooleanValue propVerboseRobotLogging;

    private static IntValue propGuideItemSearchLimit;
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
        propColourBlindMode = builder.comment("Should I enable colorblind mode?")
            .define("colorBlindMode", false);

        propUseColouredLabels = builder.comment("Should colours be displayed as their own (or a similar) colour in tooltips?")
            .define("useColouredLabels", true);

        propUseHighContrastColouredLabels = builder.comment("Should colours displayed in tooltips use higher-contrast colours?")
            .define("useHighContrastColouredLabels", false);

        propHidePower = builder.comment("Should all power values (MJ, MJ/t) be hidden?")
            .define("hidePowerValues", false);

        propHideFluid = builder.comment("Should all fluid values (Buckets, mB, mB/t) be hidden?")
            .define("hideFluidValues", false);

        propGuideBookEnableDetail = builder.define("guideBookEnableDetail", false);

        propUseBucketsStatic = builder.comment("Should static fluid values be displayed in terms of buckets rather than thousandths of a bucket? (B vs mB)")
            .define("useBucketsStatic", true);

        propUseBucketsFlow = builder.comment("Should flowing fluid values be displayed in terms of buckets per second rather than thousandths of a bucket per tick? (B/s vs mB/t)")
            .define("useBucketsFlow", true);

        propUseLongLocalizedName = builder.comment("Should localised strings be displayed in long or short form (10 mB / t vs 10 milli buckets per tick)")
            .define("useLongLocalizedName", true);

        propDisplayTimeGap = builder.comment(
            "Flow-rate display unit for BuildCraft energy values.",
            "TICKS displays values as unit/t; SECONDS displays values as unit/s."
        ).defineEnum("timeGap", TimeGap.SECONDS, TimeGap.values());

        propUseSwappableSprites = builder.comment(
            "Disable this if you get texture errors with optifine. Disables some texture switching functionality",
            "when changing config options such as colour blind mode."
        ).define("useSwappableSprites", true);

        propItemRenderRotation = builder.comment(
            "The rotation that items use when travelling through pipes. Set to 'enabled' for full rotation,",
            "'disabled' for no rotation, or 'horizontals_only' to only rotate items when going horizontally."
        ).defineEnum("itemRenderRotation", RenderRotation.ENABLED, RenderRotation.values());
        builder.pop();

        builder.push("general");
        propMinePlayerProtected = builder.comment("Should BuildCraft miners be allowed to break blocks using player-specific protection?")
            .define("miningBreaksPlayerProtectedBlocks", false);

        propChunkLoadType = builder.comment(
            "Controls whether BuildCraft machines may keep chunks loaded.",
            "AUTO enables chunk loading in singleplayer/LAN and disables it on dedicated servers."
        ).worldRestart().defineEnum("chunkLoading", ChunkLoaderType.AUTO, ChunkLoaderType.values());

        propChunkLoadLevel = builder.comment(
            "Selects which BuildCraft chunk-loading tiles are allowed to request tickets."
        ).worldRestart().defineEnum("chunkLoadLevel", ChunkLoaderLevel.SELF_TILES, ChunkLoaderLevel.values());

        propItemLifespan = builder.comment("How long, in seconds, should items stay on the ground? (Vanilla = 300, default = 60)")
            .defineInRange("itemLifespan", 60, 5, 600);

        propUseWrenchTag = builder.comment(
            "Allow items in the common c:tools/wrench tag to operate BuildCraft wrench interactions.",
            "Legacy items implementing BuildCraft's IToolWrench interface remain supported even when disabled."
        ).define("useWrenchTag", true);

        propNetworkUpdateRate = builder.comment("How often, in ticks, should network update packets be sent? Increasing this might help network performance.")
            .defineInRange("updateFactor", 10, 1, 10);

        propEnableAnimatedSprites = builder.comment("Disable this if you get sub-standard framerates due to buildcraft's ~60 sprites animating every frame.")
            .define("enableAnimatedSprites", true);

        propMaxGuideSearchResults = builder.comment("The maximum number of search results to display in the guide book.")
            .defineInRange("maxGuideSearchResults", 1200, 500, 5000);

        propGuideItemSearchLimit = builder.comment("The maximum number of items that the guide book will index.")
            .defineInRange("guideItemSearchLimit", 10_000, 1_500, 5_000_000);
        builder.pop();

        builder.push("power");
        propMjPerFe = builder.comment("MJ per 1 FE. Default 0.1 means 1 MJ = 10 FE.")
            .worldRestart().defineInRange("mjPerFe", 0.1D, 0.0001D, 0.2D);
        propPowerMode = builder.comment("MJ_ONLY, MJ_AUTOCONVERT_FE, or DISPLAY_FE")
            .worldRestart().defineEnum("powerMode", BCLibConfig.PowerMode.MJ_ONLY);
        builder.pop();

        builder.push("debug");
        propVerboseRobotLogging = builder.comment(
            "Write detailed server-side robot state, AI decisions, docking and pathfinding to latest.log every tick.",
            "Very noisy and potentially expensive. Enable only temporarily when diagnosing robotics issues."
        ).define("verboseRobotLogging", false);
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
        if (!BCCore.MODID.equals(modId)) {
            return;
        }

        minePlayerProtected = propMinePlayerProtected.get();
        verboseRobotLogging = propVerboseRobotLogging.get();
        BCLibConfig.useColouredLabels = propUseColouredLabels.get();
        BCLibConfig.useHighContrastLabelColours = propUseHighContrastColouredLabels.get();

        hidePower = propHidePower.get();
        hideFluid = propHideFluid.get();
        BCLibConfig.hidePowerValues = hidePower;
        BCLibConfig.hideFluidValues = hideFluid;

        BCLibConfig.guideShowDetail = propGuideBookEnableDetail.get();
        BCLibConfig.maxGuideSearchCount = Mth.clamp(propMaxGuideSearchResults.get(), 500, 5000);
        BCLibConfig.guideItemSearchLimit = Mth.clamp(propGuideItemSearchLimit.get(), 1_500, 5_000_000);
        BCLibConfig.useBucketsStatic = propUseBucketsStatic.get();
        BCLibConfig.useBucketsFlow = propUseBucketsFlow.get();
        BCLibConfig.useLongLocalizedName = propUseLongLocalizedName.get();
        BCLibConfig.itemLifespan = propItemLifespan.get();
        pumpsConsumeWater = BalanceConfig.machineInt("buildcraftfactory:pump", "consume_water_sources", 0) != 0;
        BCLibConfig.useWrenchTag = propUseWrenchTag.get();
        markerMaxDistance = BalanceConfig.machineInt("markers", "max_distance_blocks", 64);
        pumpMaxDistance = BalanceConfig.machineInt("buildcraftfactory:pump", "max_distance_blocks", 64);
        networkUpdateRate = Mth.clamp(propNetworkUpdateRate.get(), 1, 10);
        BCLibConfig.colourBlindMode = propColourBlindMode.get();
        BCLibConfig.displayTimeGap = propDisplayTimeGap.get();
        BCLibConfig.rotateTravelingItems = propItemRenderRotation.get();
        BCLibConfig.enableAnimatedSprites = propEnableAnimatedSprites.get();
        miningMultiplier = BalanceConfig.machine("mining", "energy_multiplier", 1);
        miningMaxDepth = BalanceConfig.machineInt("mining", "max_depth", 256);
        BCLibConfig.chunkLoadingType = propChunkLoadType.get();
        BCLibConfig.chunkLoadingLevel = propChunkLoadLevel.get();
        BCLibConfig.useSwappableSprites = propUseSwappableSprites.get();

        BCLibConfig.mjFeConversion = MjFeConversion.createParsed(propMjPerFe.get());
        BCLibConfig.powerMode = propPowerMode.get();
        BCLibConfig.refreshConfigs();
    }
}
