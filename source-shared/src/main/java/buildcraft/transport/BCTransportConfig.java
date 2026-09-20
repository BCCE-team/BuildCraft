/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */
package buildcraft.transport;

import buildcraft.api.v2.energy.MjAmount;

import buildcraft.transport.internal.pipe.EnumPipeColourType;
import buildcraft.transport.internal.pipe.PipeApi;
import buildcraft.transport.internal.pipe.PipeApi.PowerTransferInfo;
import buildcraft.transport.internal.pipe.PipeApi.ForgeEnergyTransferInfo;
import buildcraft.transport.internal.pipe.PipeDefinition;
import buildcraft.lib.misc.MathUtil;
import buildcraft.lib.platform.config.BCConfigSpec;
import buildcraft.lib.platform.config.BCConfigSpec.BooleanValue;
import buildcraft.lib.platform.config.BCConfigSpec.IntValue;

public class BCTransportConfig {

    public static BCConfigSpec config;

    private static final long MJ_REQ_MILLIBUCKET_MIN = 100;
    private static final long MJ_REQ_ITEM_MIN = 50_000;

    public static long mjPerMillibucket = 1_000;
    public static long mjPerItem = MjAmount.MICRO_MJ_PER_MJ;
    public static int baseFlowRate = 10;
    public static int baseFeRate = 40;
    public static boolean fluidPipeColourBorder;

    private static IntValue propMjPerMillibucket;
    private static IntValue propMjPerItem;
    private static IntValue propBaseFlowRate;
    private static IntValue propBaseFeRate;
    private static BooleanValue propFluidPipeColourBorder;

    public static void preInit() {
        BCConfigSpec.Builder builder = new BCConfigSpec.Builder();

        builder.push("general");
        propMjPerMillibucket = builder.worldRestart()
            .defineInRange("pipes.mjPerMillibucket", (int) mjPerMillibucket, (int) MJ_REQ_MILLIBUCKET_MIN, Integer.MAX_VALUE);

        propMjPerItem = builder.worldRestart()
            .defineInRange("pipes.mjPerItem", (int) mjPerItem, (int) MJ_REQ_ITEM_MIN, Integer.MAX_VALUE);

        propBaseFlowRate = builder.worldRestart()
            .defineInRange("pipes.baseFluidRate", baseFlowRate, 1, 40);
        propBaseFeRate = builder.worldRestart()
            .defineInRange("pipes.baseFeRate", baseFeRate, 10, 4000);
        builder.pop();

        builder.push("display");
        propFluidPipeColourBorder = builder.worldRestart()
            .define("pipes.fluidColourIsBorder", true);
        builder.pop();

        config = builder.build();
    }

    public static void reloadConfig() {
        mjPerMillibucket = Math.max(MJ_REQ_MILLIBUCKET_MIN, propMjPerMillibucket.get());
        mjPerItem = Math.max(MJ_REQ_ITEM_MIN, propMjPerItem.get());
        baseFlowRate = MathUtil.clamp(propBaseFlowRate.get(), 1, 40);
        baseFeRate = MathUtil.clamp(propBaseFeRate.get(), 10, 4000);
        int basePowerRate = 4;

        fluidPipeColourBorder = propFluidPipeColourBorder.get();
        PipeApi.flowFluids.fallbackColourType = fluidPipeColourBorder
            ? EnumPipeColourType.BORDER_INNER
            : EnumPipeColourType.TRANSLUCENT;

        fluidTransfer(BCTransportPipes.cobbleFluid, baseFlowRate, 10);
        fluidTransfer(BCTransportPipes.woodFluid, baseFlowRate, 10);

        fluidTransfer(BCTransportPipes.stoneFluid, baseFlowRate * 2, 10);
        fluidTransfer(BCTransportPipes.sandstoneFluid, baseFlowRate * 2, 10);

        fluidTransfer(BCTransportPipes.clayFluid, baseFlowRate * 4, 10);
        fluidTransfer(BCTransportPipes.ironFluid, baseFlowRate * 4, 10);
        fluidTransfer(BCTransportPipes.quartzFluid, baseFlowRate * 4, 10);

        fluidTransfer(BCTransportPipes.diamondFluid, baseFlowRate * 8, 10);
        fluidTransfer(BCTransportPipes.diaWoodFluid, baseFlowRate * 8, 10);
        fluidTransfer(BCTransportPipes.goldFluid, baseFlowRate * 8, 2);
        fluidTransfer(BCTransportPipes.voidFluid, baseFlowRate * 8, 10);

        powerTransfer(BCTransportPipes.cobblePower, basePowerRate, 16, false);
        powerTransfer(BCTransportPipes.stonePower, basePowerRate * 2, 32, false);
        powerTransfer(BCTransportPipes.woodPower, basePowerRate * 4, 128, true);
        powerTransfer(BCTransportPipes.sandstonePower, basePowerRate * 4, 32, false);
        powerTransfer(BCTransportPipes.quartzPower, basePowerRate * 8, 32, false);
        powerTransfer(BCTransportPipes.ironPower, basePowerRate * 8, 32, false);
        powerTransfer(BCTransportPipes.goldPower, basePowerRate * 16, 32, false);
        powerTransfer(BCTransportPipes.diamondPower, basePowerRate * 64, 32, false);
        powerTransfer(BCTransportPipes.diaWoodPower, basePowerRate * 64, 32, true);

        forgeEnergyTransfer(BCTransportPipes.cobbleFe, baseFeRate, false);
        forgeEnergyTransfer(BCTransportPipes.stoneFe, baseFeRate * 2, false);
        forgeEnergyTransfer(BCTransportPipes.woodFe, baseFeRate * 4, true);
        forgeEnergyTransfer(BCTransportPipes.sandstoneFe, baseFeRate * 4, false);
        forgeEnergyTransfer(BCTransportPipes.quartzFe, baseFeRate * 8, false);
        forgeEnergyTransfer(BCTransportPipes.ironFe, baseFeRate * 8, false);
        forgeEnergyTransfer(BCTransportPipes.goldFe, baseFeRate * 32, false);
        forgeEnergyTransfer(BCTransportPipes.diamondFe, baseFeRate * 64, false);
        forgeEnergyTransfer(BCTransportPipes.diaWoodFe, baseFeRate * 64, true);
    }

    private static void fluidTransfer(PipeDefinition def, int rate, int delay) {
        PipeApi.fluidTransferData.put(def, new PipeApi.FluidTransferInfo(rate, delay));
    }

    private static void powerTransfer(PipeDefinition def, int transferMultiplier, int resistanceDivisor, boolean recv) {
        long transfer = MjAmount.MICRO_MJ_PER_MJ * transferMultiplier;
        long resistance = MjAmount.MICRO_MJ_PER_MJ / resistanceDivisor;
        PipeApi.powerTransferData.put(def, PowerTransferInfo.createFromResistance(transfer, resistance, recv));
    }

    private static void forgeEnergyTransfer(PipeDefinition def, int maxTransfer, boolean receiver) {
        PipeApi.forgeEnergyTransferData.put(def, new ForgeEnergyTransferInfo(maxTransfer, receiver));
    }

    public static void onConfigLoad(String modId) {
        if (BCTransport.MODID.equals(modId)) {
            reloadConfig();
        }
    }

    public static void onConfigReload(String modId) {
        if (BCTransport.MODID.equals(modId)) {
            reloadConfig();
        }
    }
}
