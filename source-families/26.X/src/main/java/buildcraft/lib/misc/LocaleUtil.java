/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import buildcraft.lib.internal.mj.MjFormatting;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IllegalFormatException;
import java.util.Set;

import javax.annotation.Nullable;

import buildcraft.lib.internal.debug.BCDebugging;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.BCLibConfig.TimeGap;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.fluids.IFluidTank;

/** The central class for localizing objects. */
public class LocaleUtil {

    public static final boolean DEBUG = BCDebugging.shouldDebugLog("lib.locale");
    private static final Set<String> failedStrings = new HashSet<>();

    private static final NumberFormat FORMAT_FLUID = NumberFormat.getNumberInstance();

    private static String localeKeyFluidStatic, localeKeyFluidFlow;
    private static String localeKeyFluidStaticCap, localeKeyFluidStaticEmpty, localeKeyFluidStaticFull;
    private static String localeKeyMjStatic, localeKeyMjFlow;

    static {
        BCLibConfig.configChangeListeners.add(LocaleUtil::onConfigChanged);
        onConfigChanged();
    }

    /** Should be called whenever any of the {@link BCLibConfig} options are changed that affect any of the methods in
     * this class. */
    public static void onConfigChanged() {
        boolean bucketStatic = BCLibConfig.useBucketsStatic;
        boolean bucketFlow = BCLibConfig.useBucketsFlow;
        String longName = BCLibConfig.useLongLocalizedName ? "long" : "short";
        String timeGap = BCLibConfig.displayTimeGap == TimeGap.SECONDS ? "seconds." : "";
        localeKeyFluidStatic = "buildcraft.fluid.static." + (bucketStatic ? "bucket." : "milli.") + longName;
        if (BCLibConfig.displayTimeGap == TimeGap.SECONDS) {
            localeKeyFluidFlow = "buildcraft.fluid.flow." + (bucketFlow ? "bucket." : "milli.seconds.") + longName;
        } else {
            localeKeyFluidFlow = "buildcraft.fluid.flow." + (bucketFlow ? "bucket.ticks." : "milli.") + longName;
        }
        localeKeyFluidStaticCap = "buildcraft.fluid.static.cap." + (bucketStatic ? "bucket." : "milli.") + longName;
        localeKeyFluidStaticEmpty = "buildcraft.fluid.empty." + (bucketStatic ? "bucket." : "milli.") + longName;
        localeKeyFluidStaticFull = "buildcraft.fluid.full." + (bucketStatic ? "bucket." : "milli.") + longName;
        localeKeyMjStatic = "buildcraft.mj.static." + longName;
        // Flow rates are always compact: MJ/t or MJ/s. The long-name option still applies to stored MJ values.
        localeKeyMjFlow = "buildcraft.mj.flow." + timeGap + "short";
    }

    /** Localizes the give key to the current locale.
     * 
     * @param key The key to localize
     * @return The localized key, or the input key if no localization was found. */
    public static String localize(String key) {
        String localized = Language.getInstance().getOrDefault(key);
        if (localized == key) {
            if (DEBUG && failedStrings.add(localized)) {
                BCLog.logger.warn("[lib.locale] Attempted to localize '" + key + "' but no localization existed!");
            }
            return key;
        }
        return localized;
    }

    /** Localizes the given key, and performs {@link String#format(String, Object...)} with the localized value and the
     * arguments given.
     * 
     * @param key The key to localize
     * @param args The arguments to put into the localized key
     * @return The localized string. */
    public static String localize(String key, Object... args) {
        String localized = Language.getInstance().getOrDefault(key);
        if (localized == key) {
            if (DEBUG && failedStrings.add(localized)) {
                BCLog.logger.warn("[lib.locale] Attempted to localize '" + key + "' but no localization existed!");
            }
            return key + " " + Arrays.toString(args);
        }
        try {
            return String.format(localized, args);
        } catch (IllegalFormatException ife) {
            return "Bad Format: " + ife.getMessage();
        }
    }

    /** Checks to see if the given key can be localized.
     * 
     * @param key The key to check
     * @return True if the key could be localized, false if not. */
    public static boolean canLocalize(String key) {
        return I18n.exists(key);
    }

    /** @param colour The {@link DyeColor} to localize.
     * @return a localised name for the given colour. */
    public static String localizeColour(DyeColor colour) {
        return localize("color.minecraft." + colour.getName());
    }
    
    public static Component localizeColourComponent(DyeColor colour) {
    	return Component.translatable("color.minecraft." + colour.getName()).withStyle(Style.EMPTY.withColor(colour.getTextColor()));
    }

    /** @param face The {@link Direction} to localize.
     * @return a localised name for the given face. */
    public static String localizeFacing(@Nullable Direction face) {
        return localize("direction." + (face == null ? "center" : face.getName()));
    }

    public static MutableComponent localizeFluidStaticAmount(IFluidTank tank) {
        return localizeFluidStaticAmount(tank.getFluidAmount(), tank.getCapacity());
    }

    public static MutableComponent localizeFluidStaticAmount(int fluidAmount) {
        return localizeFluidStaticAmount(fluidAmount, -1);
    }

    /** Localizes the given fluid amount, out of a given capacity */
    public static MutableComponent localizeFluidStaticAmount(int fluidAmount, int capacity) {
        if (BCLibConfig.hideFluidValues) {
            return hiddenValue();
        }
        if (fluidAmount <= 0) {
            if (capacity > 0) {
                String cap;
                if (BCLibConfig.useBucketsStatic) {
                    cap = FORMAT_FLUID.format(capacity / 1000.0);
                } else {
                    cap = FORMAT_FLUID.format(capacity);
                }
                return Component.translatable(localeKeyFluidStaticEmpty, cap);
            }
            return Component.translatable("buildcraft.fluid.empty");
        } else {
            String amount;
            String cap;
            if (BCLibConfig.useBucketsStatic) {
                amount = FORMAT_FLUID.format(fluidAmount / 1000.0);
                cap = FORMAT_FLUID.format(capacity / 1000.0);
            } else {
                amount = FORMAT_FLUID.format(fluidAmount);
                cap = FORMAT_FLUID.format(capacity);
            }
            if (capacity == fluidAmount) {
                return Component.translatable(localeKeyFluidStaticFull, amount);
            }
            return Component.translatable(capacity > 0 ? localeKeyFluidStaticCap : localeKeyFluidStatic, amount, cap);
        }
    }

    public static MutableComponent localizeFluidFlow(long milliBucketsPerTick) {
        if (BCLibConfig.hideFluidValues) {
            return hiddenValue();
        }
        double amountPerGap = BCLibConfig.displayTimeGap == TimeGap.SECONDS
            ? milliBucketsPerTick * 20.0D
            : milliBucketsPerTick;
        String amount = BCLibConfig.useBucketsFlow
            ? FORMAT_FLUID.format(amountPerGap / 1000.0D)
            : FORMAT_FLUID.format(amountPerGap);
        return Component.translatable(localeKeyFluidFlow, amount);
    }

    public static MutableComponent localizeFe(long fe) {
        if (BCLibConfig.hidePowerValues) {
            return hiddenValue();
        }
        return Component.translatable("buildcraft.fe.static.short", FORMAT_FLUID.format(fe));
    }

    public static MutableComponent localizeFeFlow(long fePerTick) {
        if (BCLibConfig.hidePowerValues) {
            return hiddenValue();
        }
        long value = BCLibConfig.displayTimeGap.convertTicksToGap(fePerTick);
        String key = BCLibConfig.displayTimeGap == BCLibConfig.TimeGap.SECONDS
            ? "buildcraft.fe.flow.seconds.short"
            : "buildcraft.fe.flow.short";
        return Component.translatable(key, FORMAT_FLUID.format(value));
    }

    public static MutableComponent localizeMj(long mj) {
        if (BCLibConfig.powerMode.isDisplayFe()) {
            return localizeFe(BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjToWholeFe(mj));
        }
        if (BCLibConfig.hidePowerValues) {
            return hiddenValue();
        }
        return Component.translatable(localeKeyMjStatic, MjFormatting.formatMicroMj(mj));
    }

    public static MutableComponent localizeMjFlow(long mj) {
        if (BCLibConfig.powerMode.isDisplayFe()) {
            return localizeFeFlow(BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjToWholeFe(mj));
        }
        if (BCLibConfig.hidePowerValues) {
            return hiddenValue();
        }
        mj = BCLibConfig.displayTimeGap.convertTicksToGap(mj);
        return Component.translatable(localeKeyMjFlow, MjFormatting.formatMicroMj(mj));
    }

    private static MutableComponent hiddenValue() {
        return Component.translatable("buildcraft.value.hidden");
    }

    public static MutableComponent localizeHeat(double heat) {
        // if (BCLibConfig.useLongLocalizedName) {
        // return localize("buildcraft.heat.long", heat);
        // } else {
        return Component.literal(String.format("%.2f \u00B0C", heat));
        // }
    }
}
