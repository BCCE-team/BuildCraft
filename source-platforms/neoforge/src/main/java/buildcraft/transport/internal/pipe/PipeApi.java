package buildcraft.transport.internal.pipe;

import buildcraft.api.v2.energy.MjAmount;

import java.util.IdentityHashMap;
import java.util.Map;

import javax.annotation.Nonnull;

import buildcraft.transport.internal.IInjectable;
import buildcraft.transport.internal.pluggable.IPluggableRegistry;
import buildcraft.transport.internal.pluggable.PipePluggable;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.capabilities.BlockCapability;

/** The central holding class for all pipe related registers and methods. */
public final class PipeApi {
    public static IPipeRegistry pipeRegistry;
    public static IPluggableRegistry pluggableRegistry;
    public static IPipeExtensionManager extensionManager;
    public static PipeFlowType flowStructure;
    public static PipeFlowType flowItems;
    public static PipeFlowType flowFluids;
    public static PipeFlowType flowPower;
    public static PipeFlowType flowForgeEnergy;

    /** The default transfer information used if a pipe definition has not been registered. Note that this is replaced
     * by BuildCraft Transport to config-defined values. */
    public static FluidTransferInfo fluidInfoDefault = new FluidTransferInfo(20, 10);

    /** The default transfer information used if a pipe definition has not been registered. Note that this is replaced
     * by BuildCraft Transport to config-defined values. */
    public static PowerTransferInfo powerInfoDefault = PowerTransferInfo.createFromResistance(8 * MjAmount.MICRO_MJ_PER_MJ, MjAmount.MICRO_MJ_PER_MJ / 32, false);

    /** Default Forge Energy transfer settings for FE pipes. */
    public static ForgeEnergyTransferInfo forgeEnergyInfoDefault = new ForgeEnergyTransferInfo(100, false);

    public static final Map<PipeDefinition, FluidTransferInfo> fluidTransferData = new IdentityHashMap<>();
    public static final Map<PipeDefinition, PowerTransferInfo> powerTransferData = new IdentityHashMap<>();
    public static final Map<PipeDefinition, ForgeEnergyTransferInfo> forgeEnergyTransferData = new IdentityHashMap<>();

    @Nonnull
    public static final BlockCapability<IPipeHolder, Direction> CAP_PIPE_HOLDER =
        BlockCapability.createSided(id("pipe_holder"), IPipeHolder.class);

    @Nonnull
    public static final BlockCapability<IPipe, Direction> CAP_PIPE =
        BlockCapability.createSided(id("pipe"), IPipe.class);

    @Nonnull
    public static final BlockCapability<PipePluggable, Direction> CAP_PLUG =
        BlockCapability.createSided(id("pipe_pluggable"), PipePluggable.class);

    @Nonnull
    public static final BlockCapability<IInjectable, Direction> CAP_INJECTABLE =
        BlockCapability.createSided(id("injectable"), IInjectable.class);

    public static FluidTransferInfo getFluidTransferInfo(PipeDefinition def) {
        FluidTransferInfo info = fluidTransferData.get(def);
        if (info != null) return info;
        if (def != null && def.getApiType() != null) {
            var profile = def.getApiType().fluidProfile().orElse(null);
            if (profile != null) {
                long amount = profile.maxPerTick().milliBuckets();
                int transfer = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, amount));
                return new FluidTransferInfo(transfer, Math.max(1, profile.transferDelayTicks()));
            }
        }
        return fluidInfoDefault;
    }

    public static ForgeEnergyTransferInfo getForgeEnergyTransferInfo(PipeDefinition def) {
        ForgeEnergyTransferInfo info = forgeEnergyTransferData.get(def);
        if (info != null) return info;
        if (def != null && def.getApiType() != null) {
            var profile = def.getApiType().externalEnergyProfile().orElse(null);
            if (profile != null) {
                int transfer = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, profile.maxPerTick()));
                return new ForgeEnergyTransferInfo(transfer, profile.extractor());
            }
        }
        return forgeEnergyInfoDefault;
    }

    public static PowerTransferInfo getPowerTransferInfo(PipeDefinition def) {
        PowerTransferInfo info = powerTransferData.get(def);
        if (info != null) return info;
        if (def != null && def.getApiType() != null) {
            var profile = def.getApiType().mjProfile().orElse(null);
            if (profile != null) {
                return new PowerTransferInfo(
                    profile.maxPerTick().microMj(),
                    profile.lossAtFullTransfer().microMj(),
                    profile.resistancePerTick(),
                    profile.extractor()
                );
            }
        }
        return powerInfoDefault;
    }

    public static class FluidTransferInfo {
        /** Controls the maximum amount of fluid that can be transferred around and out of a pipe per tick. Note that
         * this does not affect the flow rate coming into the pipe. */
        public final int transferPerTick;

        /** Controls how long the pipe should delay incoming fluids by. Minimum value is 1, because of the way that
         * fluids are handled internally. This value is multiplied by the fluids viscosity, and divided by 100 to give
         * the actual delay. */
        public final double transferDelayMultiplier;

        /**
         * Internal per-section buffer. The buffer must hold at least one bucket and at least one complete
         * configured transfer-delay window, so custom high-rate/slow pipes cannot overflow merely because their
         * delay differs from BuildCraft's 10-tick baseline.
         */
        public final int bufferCapacity;

        public FluidTransferInfo(int transferPerTick, int transferDelay) {
            this.transferPerTick = Math.max(0, transferPerTick);
            if (transferDelay <= 0) {
                transferDelay = 1;
            }
            this.transferDelayMultiplier = transferDelay;
            long delayWindow = (long) this.transferPerTick * transferDelay;
            this.bufferCapacity = (int) Math.min(Integer.MAX_VALUE, Math.max(1000L, delayWindow));
        }
    }

    public static class ForgeEnergyTransferInfo {
        /** Maximum FE transferred through this pipe per tick. */
        public final int transferPerTick;
        /** True for wooden and diamond-wood extraction pipes. */
        public final boolean isReceiver;

        public ForgeEnergyTransferInfo(int transferPerTick, boolean isReceiver) {
            this.transferPerTick = Math.max(1, transferPerTick);
            this.isReceiver = isReceiver;
        }
    }

    public static class PowerTransferInfo {
        public final long transferPerTick;
        public final long lossPerTick;
        /** The percentage resistance per tick. Should be a value between 0 and {@link MjAmount#MICRO_MJ_PER_MJ} */
        public final long resistancePerTick;
        public final boolean isReceiver;

        /** Sets resistancePerTick to be equal to lossPerTick when full power is being transferred, scaling down to 0.
         * 
         * @param transferPerTick
         * @param lossPerTick
         * @param isReceiver */
        public static PowerTransferInfo createFromLoss(long transferPerTick, long lossPerTick, boolean isReceiver) {
            return new PowerTransferInfo(transferPerTick, lossPerTick, lossPerTick * MjAmount.MICRO_MJ_PER_MJ / transferPerTick, isReceiver);
        }

        /** Sets lossPerTick to be equal to resistancePerTick when full power is being transferred.
         * 
         * @param transferPerTick
         * @param resistancePerTick
         * @param isReceiver */
        public static PowerTransferInfo createFromResistance(long transferPerTick, long resistancePerTick, boolean isReceiver) {
            long lossPerTick = resistancePerTick * transferPerTick / MjAmount.MICRO_MJ_PER_MJ;
            return new PowerTransferInfo(transferPerTick, lossPerTick, resistancePerTick, isReceiver);
        }

        public PowerTransferInfo(long transferPerTick, long lossPerTick, long resistancePerTick, boolean isReceiver) {
            if (transferPerTick < 10) {
                transferPerTick = 10;
            }
            this.transferPerTick = transferPerTick;
            this.lossPerTick = lossPerTick;
            this.resistancePerTick = resistancePerTick;
            this.isReceiver = isReceiver;
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("buildcraftlib", path);
    }
}

