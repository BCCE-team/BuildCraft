/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team This Source Code Form is subject to the terms of the Mozilla
 * Public License, v. 2.0. If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import buildcraft.lib.internal.mj.MjFeConversion;
import buildcraft.lib.chunkload.IChunkLoadingTile.LoadType;
import buildcraft.lib.misc.LocaleUtil;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;

/** Configuration file for lib. In order to keep lib as close to being just a library mod as possible, these are not set
 * by a config file, but instead by BC Core. Feel free to set them yourself, from your own configs, if you do not depend
 * on BC COre itself, and it might not be loaded in the mod environment. */
public class BCLibConfig {

    public static File guiConfigFile = null;

    /** If true then items and blocks will display the colour of an item (one of {@link DyeColor}) with the correct
     * {@link TextFormatting} colour value.<br>
     * This changes the behaviour of {@link ColourUtil#convertColourToTextFormat(DyeColor)}. */
    public static boolean useColouredLabels = true;

    /** If this and {@link #useColouredLabels} is true then only colours which strongly contrast with the base colour
     * will be used. Useful if you can't read dark-gray on black (for example) */
    public static boolean useHighContrastLabelColours = false;

    /** If true then user-facing power values are replaced with a hidden-value label. */
    public static boolean hidePowerValues = false;

    /** If true then user-facing fluid amounts and flow rates are replaced with a hidden-value label. */
    public static boolean hideFluidValues = false;

    /** If true then applicable visual elements will be displayed in more colourblind friendly way. */
    public static boolean colourBlindMode = false;

    /** If true, items in the common {@code c:tools/wrench} tag are accepted as BuildCraft wrenches.
     * Items implementing the legacy {@link buildcraft.lib.internal.tool.IToolWrench} contract are always accepted. */
    public static boolean useWrenchTag = true;

    /** The lifespan (in seconds) that spawned items will have, when dropped by a quarry or builder (etc) */
    public static int itemLifespan = 60;

    /** If true then fluidstacks will localize with something similar to "4B Water" rather than "4000mB of Water" when
     * calling {@link LocaleUtil#localizeFluidStaticAmount(int)} */
    public static boolean useBucketsStatic = true;

    /** If true then fluid flow values use buckets rather than millibuckets. The /s versus /t suffix is controlled
     * independently by {@link #displayTimeGap}. */
    public static boolean useBucketsFlow = true;

    /** If true then fluidstacks and Mj will be localized with longer names (for example "1.2 Buckets per second" rather
     * than "60mB/t") */
    public static boolean useLongLocalizedName = false;

    /** If true then {@link AtlasSpriteVariants#createForConfig(net.minecraft.util.Identifier)} will retun
     * {@link AtlasSpriteSwappable}, allowing for instant reloads when switching between colourblind modes and other
     * changable things. If false it will return a normal {@link TextureAtlasSprite}. Disabling this might help if you
     * get sprite issues with mods like optifine. */
    public static boolean useSwappableSprites = true;

    /** If false then {@link AtlasSpriteVariants#updateAnimation()} will never update the animation for wrapped
     * sprites. */
    public static boolean enableAnimatedSprites = true;

    /** The maximum number of results to display in the guide contents page for the search bar. */
    public static int maxGuideSearchCount = 1200;

    public static TimeGap displayTimeGap = TimeGap.SECONDS;

    /** Controls the preferred orientation of travelling item rendering. */
    public static RenderRotation rotateTravelingItems = RenderRotation.ENABLED;

    public static ChunkLoaderType chunkLoadingType = ChunkLoaderType.AUTO;

    public static ChunkLoaderLevel chunkLoadingLevel = ChunkLoaderLevel.SELF_TILES;

    public static boolean guideShowDetail = false;

    /** The maximum number of items that the guide book will index. */
    public static int guideItemSearchLimit = 10_000;

    /** MJ/FE conversion. Default matches BuildCraft 8: 1 MJ = 10 FE. */
    public static MjFeConversion mjFeConversion = MjFeConversion.createDefault();

    /** Controls automatic Forge Energy compatibility for ordinary BuildCraft machines. */
    public static PowerMode powerMode = PowerMode.MJ_ONLY;

    public static final List<Runnable> configChangeListeners = new ArrayList<>();

    /** Resets cached values across various BCLib classes that rely on these config options. */
    public static void refreshConfigs() {
        for (Runnable r : configChangeListeners) {
            r.run();
        }
    }

    public enum PowerMode {
        /** Pure BuildCraft MJ. Dedicated FE Engine / MJ Dynamo / FE pipes are still available. */
        MJ_ONLY(false, false),
        /** Ordinary BuildCraft machines accept FE and engines can feed FE consumers. */
        MJ_AUTOCONVERT_FE(true, false),
        /** Same conversion behaviour, but user-facing generic power readouts prefer FE. */
        DISPLAY_FE(true, true);

        private final boolean autoconvert;
        private final boolean displayFe;

        PowerMode(boolean autoconvert, boolean displayFe) {
            this.autoconvert = autoconvert;
            this.displayFe = displayFe;
        }

        public boolean isAutoconvertEnabled() { return autoconvert; }
        public boolean isDisplayFe() { return displayFe; }
    }

    public enum TimeGap {
        TICKS(1),
        SECONDS(20);

        private final int ticksInGap;

        TimeGap(int ticksInGap) {
            this.ticksInGap = ticksInGap;
        }

        public int convertTicksToGap(int ticks) {
            return ticks * ticksInGap;
        }

        public long convertTicksToGap(long ticks) {
            return ticks * ticksInGap;
        }

        public float convertTicksToGap(float ticks) {
            return ticks * ticksInGap;
        }

        public double convertTicksToGap(double ticks) {
            return ticks * ticksInGap;
        }
    }

    public enum RenderRotation {
        DISABLED {
            public Direction changeFacing(Direction dir) {
                return Direction.EAST;
            }
        },
        HORIZONTALS_ONLY {
            public Direction changeFacing(Direction dir) {
                return dir.getAxis() == Axis.Y ? Direction.EAST : dir;
            }
        },
        ENABLED {
            public Direction changeFacing(Direction dir) {
                return dir;
            }
        };

        public abstract Direction changeFacing(Direction dir);
    }

    public enum ChunkLoaderType {
        /** Automatic chunkloading is ENABLED. */
        ON,

        /** Automatic chunkloading is ENABLED when using the integrated server (singleplayer + LAN), and DISABLED when
         * using a dedicated server. */
        AUTO,

        /** Automatic chunkloading is DISABLED. Even for strict tiles (like the quarry) */
        OFF
    }

    public enum ChunkLoaderLevel {
        /** No automatic chunkloading is done. */
        NONE,

        /** {@link EntityBlock}'s that implement the {@link IChunkLoadingTile} interface will be loaded, provided they
         * return {@link buildcraft.lib.chunkload.IChunkLoadingTile.LoadType#HARD} */
        STRICT_TILES,

        /** {@link EntityBlock}'s that implement the {@link IChunkLoadingTile} interface will be loaded, provided they
         * DON'T return null. */
        SELF_TILES,

        /** All {@link EntityBlock}'s in the world. */
        ALL_TILES;

        public boolean canLoad(LoadType loadType) {
            switch (this) {
                case NONE:
                    return false;
                case STRICT_TILES:
                    return loadType == LoadType.HARD;
                case SELF_TILES:
                case ALL_TILES:
                    return true;
                default:
                    throw new IllegalStateException("Unknown ChunkLoaderLevel " + this);
            }
        }
    }
}
