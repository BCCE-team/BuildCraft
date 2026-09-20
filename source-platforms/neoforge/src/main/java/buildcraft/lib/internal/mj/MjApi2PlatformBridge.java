package buildcraft.lib.internal.mj;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjConnectionContext;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjPortDescriptor;
import buildcraft.api.v2.energy.MjPortRole;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.lib.internal.api.v2.energy.MjRuntimeLookup;
import buildcraft.lib.misc.CapUtil;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** NeoForge capability compatibility lives in Lib; addons only see EnergyService/MjPort. */
public final class MjApi2PlatformBridge {
    private static final ResourceLocation NETWORK_ID = ResourceLocation.fromNamespaceAndPath("buildcraft", "mj");
    private static final MjRuntimeLookup.Backend BACKEND = new Backend();
    private static final MjAmount UNKNOWN_RATE = MjAmount.ofMicro(Long.MAX_VALUE);

    private MjApi2PlatformBridge() {}

    public static void install() {
        MjRuntimeLookup.install(BACKEND);
    }

    private static final class Backend implements MjRuntimeLookup.Backend {
        @Override
        public Optional<MjPort> port(Level level, BlockPos pos, Direction side) {
            Endpoint endpoint = endpoint(level, pos, side);
            if (!endpoint.isEmpty()) return Optional.of(endpoint);
            FeEndpoint fe = feEndpoint(level, pos, side);
            return fe == null ? Optional.empty() : Optional.of(fe);
        }

        @Override
        public Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side) {
            Endpoint endpoint = endpoint(level, pos, side);
            if (!endpoint.isEmpty()) return Optional.of(endpoint.descriptor());
            FeEndpoint fe = feEndpoint(level, pos, side);
            return fe == null ? Optional.empty() : Optional.of(fe.descriptor());
        }

        @Override
        public boolean canConnect(MjConnectionContext context) {
            BlockPos remotePos = context.position().relative(context.side());
            IMjConnector local = context.level().getCapability(MjCapabilities.CAP_CONNECTOR, context.position(), context.side());
            IMjConnector remote = context.level().getCapability(MjCapabilities.CAP_CONNECTOR, remotePos, context.side().getOpposite());
            return local == null || remote == null || (local.canConnect(remote) && remote.canConnect(local));
        }
    }

    private static Endpoint endpoint(Level level, BlockPos pos, Direction side) {
        IMjConnector connector = level.getCapability(MjCapabilities.CAP_CONNECTOR, pos, side);
        IMjReceiver receiver = level.getCapability(MjCapabilities.CAP_RECEIVER, pos, side);
        IMjRedstoneReceiver redstone = level.getCapability(MjCapabilities.CAP_REDSTONE_RECEIVER, pos, side);
        IMjReadable readable = level.getCapability(MjCapabilities.CAP_READABLE, pos, side);
        IMjPassiveProvider provider = level.getCapability(MjCapabilities.CAP_PASSIVE_PROVIDER, pos, side);
        return connector == null && receiver == null && redstone == null && readable == null && provider == null
            ? Endpoint.EMPTY
            : new Endpoint(connector, receiver, redstone, readable, provider);
    }

    private static FeEndpoint feEndpoint(Level level, BlockPos pos, Direction side) {
        if (!BuildCraftApi.service(BuildCraftServices.ENERGY).automaticFeConversionEnabled()) return null;
        IEnergyStorage fe = CapUtil.getEnergyStorage(level, pos, side);
        return fe != null && (fe.canReceive() || fe.canExtract()) ? new FeEndpoint(fe) : null;
    }

    private static final class FeEndpoint implements MjPort {
        private final IEnergyStorage fe;
        private FeEndpoint(IEnergyStorage fe) { this.fe = fe; }

        @Override
        public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            if (!fe.canReceive() || offered.isZero()) return MjTransferResult.none(offered);
            long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
            long wholeFe = offered.microMj() / ratio;
            if (wholeFe <= 0) return MjTransferResult.none(offered);
            int offeredFe = (int) Math.min(Integer.MAX_VALUE, wholeFe);
            int accepted = fe.receiveEnergy(offeredFe, mode == OperationMode.SIMULATE);
            return MjTransferResult.of(offered, MjAmount.ofMicro((long) Math.max(0, accepted) * ratio));
        }

        @Override
        public MjTransferResult extract(MjAmount requested, OperationMode mode) {
            if (!fe.canExtract() || requested.isZero()) return MjTransferResult.none(requested);
            long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
            long wholeFe = requested.microMj() / ratio;
            if (wholeFe <= 0) return MjTransferResult.none(requested);
            int requestFe = (int) Math.min(Integer.MAX_VALUE, wholeFe);
            int extracted = fe.extractEnergy(requestFe, mode == OperationMode.SIMULATE);
            return MjTransferResult.of(requested, MjAmount.ofMicro((long) Math.max(0, extracted) * ratio));
        }

        @Override public MjAmount stored() { return MjAmount.ofMicro(BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().feToMicroMj(Math.max(0, fe.getEnergyStored()))); }
        @Override public MjAmount capacity() { return MjAmount.ofMicro(BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().feToMicroMj(Math.max(0, fe.getMaxEnergyStored()))); }
        @Override public boolean canInsert() { return fe.canReceive(); }
        @Override public boolean canExtract() { return fe.canExtract(); }

        private MjPortDescriptor descriptor() {
            EnumSet<MjPortRole> roles = EnumSet.of(MjPortRole.CONNECTOR, MjPortRole.READABLE);
            if (fe.canReceive()) roles.add(MjPortRole.CONSUMER);
            if (fe.canExtract()) roles.add(MjPortRole.PROVIDER);
            // A descriptor is topology metadata, not a live transfer probe. Some FE implementations only permit
            // receive/extract calls on the logical server, so simulation here can crash client-side placement/rendering.
            // FE has no loader-neutral max-transfer accessor; advertise an unknown/unbounded structural rate instead.
            return new MjPortDescriptor(
                NETWORK_ID,
                roles,
                fe.canReceive() ? UNKNOWN_RATE : MjAmount.ZERO,
                fe.canExtract() ? UNKNOWN_RATE : MjAmount.ZERO
            );
        }
    }

    private static final class Endpoint implements MjPort {
        private static final Endpoint EMPTY = new Endpoint(null, null, null, null, null);
        private final IMjConnector connector;
        private final IMjReceiver receiver;
        private final IMjRedstoneReceiver redstone;
        private final IMjReadable readable;
        private final IMjPassiveProvider provider;

        private Endpoint(IMjConnector connector, IMjReceiver receiver, IMjRedstoneReceiver redstone,
                         IMjReadable readable, IMjPassiveProvider provider) {
            this.connector = connector;
            this.receiver = receiver;
            this.redstone = redstone;
            this.readable = readable;
            this.provider = provider;
        }

        private boolean isEmpty() {
            return connector == null && receiver == null && redstone == null && readable == null && provider == null;
        }

        @Override
        public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            if (receiver == null || !receiver.canReceive() || offered.isZero()) return MjTransferResult.none(offered);
            long remainder = receiver.receivePower(offered.microMj(), mode == OperationMode.EXECUTE ? FluidAction.EXECUTE : FluidAction.SIMULATE);
            remainder = Math.max(0L, Math.min(offered.microMj(), remainder));
            return MjTransferResult.of(offered, MjAmount.ofMicro(offered.microMj() - remainder));
        }

        @Override
        public MjTransferResult extract(MjAmount requested, OperationMode mode) {
            if (provider == null || requested.isZero()) return MjTransferResult.none(requested);
            long moved = provider.extractPower(0L, requested.microMj(), mode == OperationMode.EXECUTE);
            moved = Math.max(0L, Math.min(requested.microMj(), moved));
            return MjTransferResult.of(requested, MjAmount.ofMicro(moved));
        }

        @Override public MjAmount stored() { return MjAmount.ofMicro(readable == null ? 0L : Math.max(0L, readable.getStored())); }
        @Override public MjAmount capacity() { return MjAmount.ofMicro(readable == null ? 0L : Math.max(0L, readable.getCapacity())); }
        @Override public boolean canInsert() { return receiver != null && receiver.canReceive(); }
        @Override public boolean canExtract() { return provider != null; }

        private MjPortDescriptor descriptor() {
            EnumSet<MjPortRole> roles = EnumSet.noneOf(MjPortRole.class);
            if (connector != null) roles.add(MjPortRole.CONNECTOR);
            if (receiver != null) roles.add(MjPortRole.CONSUMER);
            if (redstone != null) roles.add(MjPortRole.REDSTONE_RECEIVER);
            if (readable != null) roles.add(MjPortRole.READABLE);
            if (provider != null) roles.add(MjPortRole.PASSIVE_PROVIDER);
            // Legacy getPowerRequested()/extractPower() are live operations. Wooden item/fluid pipes, for example,
            // reach server-only extraction code even for simulation, so descriptor discovery must never invoke them.
            return new MjPortDescriptor(
                NETWORK_ID,
                roles,
                receiver == null ? MjAmount.ZERO : UNKNOWN_RATE,
                provider == null ? MjAmount.ZERO : UNKNOWN_RATE
            );
        }
    }
}
