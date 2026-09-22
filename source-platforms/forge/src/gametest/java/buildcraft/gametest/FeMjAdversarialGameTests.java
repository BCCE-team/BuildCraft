package buildcraft.gametest;

import javax.annotation.Nonnull;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.lib.BCLib;
import buildcraft.lib.BCLibConfig;
import buildcraft.lib.internal.mj.IMjConnector;
import buildcraft.lib.internal.mj.IMjReadable;
import buildcraft.lib.internal.mj.IMjReceiver;
import buildcraft.lib.internal.mj.MjFeConversion;
import buildcraft.lib.internal.mj.MjReceiverEnergyStorage;
import buildcraft.lib.internal.mj.MjToFeAutoConverter;

@GameTestHolder(BCLib.MODID)
@PrefixGameTestTemplate(false)
public final class FeMjAdversarialGameTests {
    private static final String EMPTY_TEMPLATE = "empty3x3x3";

    private FeMjAdversarialGameTests() {
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void automaticFeCompatibilityConfigActuallyGatesAdapters(GameTestHelper helper) {
        BCLibConfig.PowerMode previous = BCLibConfig.powerMode;
        try {
            TestEnergyStorage fe = new TestEnergyStorage(1_000);
            TestMjReceiver mj = new TestMjReceiver(Long.MAX_VALUE / 4);

            BCLibConfig.powerMode = BCLibConfig.PowerMode.MJ_ONLY;
            require(helper, MjToFeAutoConverter.createReceiver(fe) == null,
                "MJ_ONLY still exposed the MJ -> FE automatic converter");
            require(helper, !new MjReceiverEnergyStorage(mj).canReceive(),
                "MJ_ONLY still exposed an FE -> MJ automatic receiver");

            BCLibConfig.powerMode = BCLibConfig.PowerMode.MJ_AUTOCONVERT_FE;
            require(helper, MjToFeAutoConverter.createReceiver(fe) != null,
                "MJ_AUTOCONVERT_FE did not expose the MJ -> FE converter");
            require(helper, new MjReceiverEnergyStorage(mj).canReceive(),
                "MJ_AUTOCONVERT_FE did not expose the FE -> MJ receiver");
        } finally {
            BCLibConfig.powerMode = previous;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void supportedMjPerFeRatiosRemainConservative(GameTestHelper helper) {
        MjFeConversion previous = BCLibConfig.mjFeConversion;
        try {
            long[] ratios = {
                MjFeConversion.MIN_MJ_PER_FE,
                MjFeConversion.DEFAULT_MJ_PER_FE,
                MjFeConversion.MAX_MJ_PER_FE
            };
            for (long ratio : ratios) {
                BCLibConfig.mjFeConversion = MjFeConversion.createRaw(ratio);
                var conversion = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion();
                long fe = 12_345;
                long microMj = conversion.feToMicroMj(fe);
                require(helper, microMj == fe * ratio, "configured MJ/FE ratio was not applied exactly");
                require(helper, conversion.microMjToWholeFe(microMj) == fe,
                    "exact FE -> MJ -> FE conversion was not reversible");
                require(helper, conversion.microMjToWholeFe(microMj + ratio - 1) == fe,
                    "fractional conversion rounded upward and created FE");
                require(helper, conversion.conversionRemainder(microMj + ratio - 1) == ratio - 1,
                    "conversion remainder did not preserve sub-FE MJ");
            }
        } finally {
            BCLibConfig.mjFeConversion = previous;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void chainedAutomaticConvertersPreserveWholeFeAndMjRemainder(GameTestHelper helper) {
        BCLibConfig.PowerMode previousMode = BCLibConfig.powerMode;
        try {
            BCLibConfig.powerMode = BCLibConfig.PowerMode.MJ_AUTOCONVERT_FE;
            long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
            TestMjReceiver sink = new TestMjReceiver(1_000L * ratio);
            IEnergyStorage feView = new MjReceiverEnergyStorage(sink);
            IMjReceiver chained = MjToFeAutoConverter.createReceiver(feView);
            require(helper, chained != null, "failed to construct MJ -> FE -> MJ converter chain");

            long wholeFe = 137;
            long remainder = Math.max(1, ratio / 2);
            long offered = wholeFe * ratio + remainder;
            long simulatedLeftover = chained.receivePower(offered, FluidAction.SIMULATE);
            require(helper, simulatedLeftover == remainder, "converter chain simulation lost the MJ remainder");
            require(helper, sink.stored == 0, "converter chain simulation mutated the MJ sink");

            long executedLeftover = chained.receivePower(offered, FluidAction.EXECUTE);
            require(helper, executedLeftover == remainder, "converter chain execute disagreed with simulation");
            require(helper, sink.stored == wholeFe * ratio,
                "multiple converters created or destroyed whole-FE-equivalent MJ");
        } finally {
            BCLibConfig.powerMode = previousMode;
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = BCLib.MODID, template = EMPTY_TEMPLATE, timeoutTicks = 20)
    public static void malformedFeAcceptanceCannotCreateMjOrBreakMetadata(GameTestHelper helper) {
        BCLibConfig.PowerMode previousMode = BCLibConfig.powerMode;
        try {
            BCLibConfig.powerMode = BCLibConfig.PowerMode.MJ_AUTOCONVERT_FE;
            long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
            IMjReceiver converter = MjToFeAutoConverter.createReceiver(new MalformedEnergyStorage());
            require(helper, converter != null, "failed to construct automatic FE converter");

            long offered = 7L * ratio + 1;
            require(helper, converter.receivePower(offered, FluidAction.SIMULATE) == 1,
                "invalid FE simulation result created or lost MJ");
            require(helper, converter.receivePower(offered, FluidAction.EXECUTE) == 1,
                "invalid FE execute result created or lost MJ");
            require(helper, converter.getStored() == 0 && converter.getCapacity() == 0,
                "negative FE metadata escaped into the MJ adapter");
        } finally {
            BCLibConfig.powerMode = previousMode;
        }
        helper.succeed();
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private static final class TestEnergyStorage implements IEnergyStorage {
        private final int capacity;
        private int stored;

        private TestEnergyStorage(int capacity) {
            this.capacity = capacity;
        }

        @Override public int receiveEnergy(int maxReceive, boolean simulate) {
            int accepted = Math.min(Math.max(0, maxReceive), capacity - stored);
            if (!simulate) stored += accepted;
            return accepted;
        }
        @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return stored; }
        @Override public int getMaxEnergyStored() { return capacity; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    }

    private static final class TestMjReceiver implements IMjReceiver, IMjReadable {
        private final long capacity;
        private long stored;

        private TestMjReceiver(long capacity) {
            this.capacity = capacity;
        }

        @Override public long getPowerRequested() { return Math.max(0, capacity - stored); }
        @Override public long receivePower(long microJoules, FluidAction action) {
            long accepted = Math.min(Math.max(0, microJoules), getPowerRequested());
            if (action.execute()) stored += accepted;
            return microJoules - accepted;
        }
        @Override public boolean canConnect(@Nonnull IMjConnector other) { return true; }
        @Override public long getStored() { return stored; }
        @Override public long getCapacity() { return capacity; }
    }

    private static final class MalformedEnergyStorage implements IEnergyStorage {
        @Override public int receiveEnergy(int maxReceive, boolean simulate) { return maxReceive == 0 ? -1 : Integer.MAX_VALUE; }
        @Override public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return -1; }
        @Override public int getMaxEnergyStored() { return -1; }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return true; }
    }
}
