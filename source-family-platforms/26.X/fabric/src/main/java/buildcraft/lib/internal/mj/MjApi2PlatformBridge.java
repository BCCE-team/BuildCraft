package buildcraft.lib.internal.mj;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjConnectionContext;
import buildcraft.api.v2.energy.MjDemandPort;
import buildcraft.api.v2.energy.MjPassivePort;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjPortDescriptor;
import buildcraft.api.v2.energy.MjPortRole;
import buildcraft.api.v2.energy.MjPortProvider;
import buildcraft.api.v2.machine.MachineView;
import buildcraft.api.v2.machine.MachineHostProvider;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.lib.internal.api.v2.MachineRuntimeView;
import buildcraft.lib.internal.api.v2.energy.MjRuntimeLookup;
import buildcraft.lib.internal.transfer.FabricTransactionScope;
import java.util.EnumSet;
import java.util.Optional;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import team.reborn.energy.api.EnergyStorage;

/**
 * Loader-neutral MJ service backed by native Fabric block lookups, then an
 * optional Team Reborn Energy adapter. Direct MJ ports take precedence over FE
 * while the adapter follows BCCE's exact configured MJ:FE conversion ratio.
 *
 * MJ_PORT and MJ_DESCRIPTOR also offer compatibility fallbacks for BCCE's
 * API v2 machines and classic MJ capability handlers. External addons can
 * register their own native Fabric providers with higher priority.
 */
public final class MjApi2PlatformBridge {
    private static final Identifier NETWORK_ID = Identifier.fromNamespaceAndPath("buildcraft", "mj");
    private static final MjAmount UNKNOWN_RATE = MjAmount.ofMicro(Long.MAX_VALUE);

    public static final BlockApiLookup<MjPort, Direction> MJ_PORT =
        BlockApiLookup.get(Identifier.fromNamespaceAndPath("buildcraftlib", "mj_port"),
            MjPort.class, Direction.class);
    public static final BlockApiLookup<MjPortDescriptor, Direction> MJ_DESCRIPTOR =
        BlockApiLookup.get(Identifier.fromNamespaceAndPath("buildcraftlib", "mj_port_descriptor"),
            MjPortDescriptor.class, Direction.class);

    /** Optional native connector contract for Fabric addons using classic MJ connectors. */
    public static final BlockApiLookup<IMjConnector, Direction> MJ_CONNECTOR =
        BlockApiLookup.get(Identifier.fromNamespaceAndPath("buildcraftlib", "mj_connector"),
            IMjConnector.class, Direction.class);

    private static final MjRuntimeLookup.Backend BACKEND = new Backend();
    private static boolean lookupsInstalled;

    private MjApi2PlatformBridge() {}

    public static synchronized void install() {
        if (!lookupsInstalled) {
            // Native Fabric addons can register a more specific block provider.
            // Legacy BCCE and addon tiles continue to expose their MjPortProvider / MachineView
            // endpoints automatically, including their correct per-side descriptors.
            MJ_PORT.registerFallback((level, pos, state, tile, side) -> declaredPort(tile, side));
            MJ_DESCRIPTOR.registerFallback((level, pos, state, tile, side) -> declaredDescriptor(tile, side));
            MJ_CONNECTOR.registerFallback((level, pos, state, tile, side) ->
                tile instanceof IBCCapabilityProvider provider
                    ? provider.getCapability(MjCapabilities.CAP_CONNECTOR, side) : null);
            lookupsInstalled = true;
        }
        MjRuntimeLookup.install(BACKEND);
    }

    private static MjPort declaredPort(BlockEntity tile, Direction side) {
        if (tile instanceof MjPortProvider provider) {
            Optional<MjPort> port = provider.mjPort(side);
            if (port.isPresent()) return port.get();
        }
        // MachineRuntimeView.mjPort() delegates to MjRuntimeLookup.port(),
        // which calls Backend.port() -> MJ_PORT.find() -> this fallback.
        // Calling it here creates an infinite lookup cycle (StackOverflowError).
        // These BCCE runtime views are resolved through their native legacy
        // capabilities below; explicit MjPortProvider endpoints remain preferred.
        if (tile instanceof MachineView machine && !(tile instanceof MachineRuntimeView)) {
            Optional<MjPort> port = machine.mjPort(side);
            if (port.isPresent()) return port.get();
        }
        if (tile instanceof MachineHostProvider provider) {
            Optional<MjPort> port = provider.machineHost().mjPort(side);
            if (port.isPresent()) return port.get();
        }
        return legacyPort(tile, side);
    }

    private static MjPort legacyPort(BlockEntity tile, Direction side) {
        if (!(tile instanceof IBCCapabilityProvider provider)) return null;
        IMjConnector connector = provider.getCapability(MjCapabilities.CAP_CONNECTOR, side);
        IMjReceiver receiver = provider.getCapability(MjCapabilities.CAP_RECEIVER, side);
        IMjRedstoneReceiver redstone = provider.getCapability(MjCapabilities.CAP_REDSTONE_RECEIVER, side);
        IMjReadable readable = provider.getCapability(MjCapabilities.CAP_READABLE, side);
        IMjPassiveProvider passive = provider.getCapability(MjCapabilities.CAP_PASSIVE_PROVIDER, side);
        if (connector == null && receiver == null && redstone == null && readable == null && passive == null)
            return null;
        return new LegacyPort(connector, receiver, redstone, readable, passive);
    }

    private static MjPortDescriptor declaredDescriptor(BlockEntity tile, Direction side) {
        if (tile instanceof MjPortProvider provider) {
            Optional<MjPort> port = provider.mjPort(side);
            if (port.isPresent()) {
                return provider.mjPortDescriptor(side).orElseGet(() -> descriptorFrom(port.get()));
            }
        }
        MjPort port = declaredPort(tile, side);
        if (port instanceof LegacyPort legacy) return legacy.descriptor();
        return port == null ? null : descriptorFrom(port);
    }

    private static boolean valid(Level level, BlockPos pos) {
        return level != null && pos != null && level.hasChunkAt(pos);
    }

    private static MjPort localPort(Level level, BlockPos pos, Direction side) {
        return MJ_PORT.find(level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side);
    }

    private static EnergyStorage external(Level level, BlockPos pos, Direction side) {
        if (!BuildCraftApi.service(BuildCraftServices.ENERGY).automaticFeConversionEnabled()) {
            return null;
        }
        EnergyStorage energy = EnergyStorage.SIDED.find(
            level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side);
        return energy == null || (!energy.supportsInsertion() && !energy.supportsExtraction())
            ? null : energy;
    }

    private static final class Backend implements MjRuntimeLookup.Backend {
        public Optional<MjPort> port(Level level, BlockPos pos, Direction side) {
            if (!valid(level, pos)) return Optional.empty();
            MjPort direct = localPort(level, pos, side);
            // Same policy as NeoForge: an actual receiving MJ endpoint wins.
            if (direct != null && direct.canInsert()) return Optional.of(direct);
            EnergyStorage fallback = external(level, pos, side);
            if (fallback != null && fallback.supportsInsertion()) return Optional.of(new FePort(fallback));
            return direct != null ? Optional.of(direct) : Optional.ofNullable(fallback).map(FePort::new);
        }

        public Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side) {
            if (!valid(level, pos)) return Optional.empty();
            MjPort direct = localPort(level, pos, side);
            MjPortDescriptor directDescriptor = MJ_DESCRIPTOR.find(
                level, pos, level.getBlockState(pos), level.getBlockEntity(pos), side);
            if (direct != null && direct.canInsert()) {
                return Optional.ofNullable(directDescriptor == null ? descriptorFrom(direct) : directDescriptor);
            }
            EnergyStorage fallback = external(level, pos, side);
            if (fallback != null && fallback.supportsInsertion()) {
                return Optional.of(new FePort(fallback).descriptor());
            }
            if (direct != null) {
                return Optional.of(directDescriptor == null ? descriptorFrom(direct) : directDescriptor);
            }
            return Optional.ofNullable(fallback).map(f -> new FePort(f).descriptor());
        }

        public boolean canConnect(MjConnectionContext context) {
            // Network compatibility is necessary, but not sufficient. Classic MJ connectors
            // may restrict the neighbor's connector type or direction on *either* side.
            if (!context.local().networkId().equals(context.remote().networkId())) return false;
            Level level = context.level();
            BlockPos remotePos = context.position().relative(context.side());
            if (!valid(level, context.position()) || !valid(level, remotePos)) return false;
            IMjConnector local = MJ_CONNECTOR.find(level, context.position(),
                level.getBlockState(context.position()), level.getBlockEntity(context.position()), context.side());
            IMjConnector remote = MJ_CONNECTOR.find(level, remotePos,
                level.getBlockState(remotePos), level.getBlockEntity(remotePos), context.side().getOpposite());
            return local == null || remote == null ||
                (local.canConnect(remote) && remote.canConnect(local));
        }
    }

    private static MjPortDescriptor descriptorFrom(MjPort port) {
        EnumSet<MjPortRole> roles = EnumSet.of(MjPortRole.CONNECTOR, MjPortRole.READABLE);
        if (port.canInsert()) roles.add(MjPortRole.CONSUMER);
        if (port.canExtract()) roles.add(MjPortRole.PROVIDER);
        return new MjPortDescriptor(
            NETWORK_ID,
            roles,
            port.canInsert() ? UNKNOWN_RATE : MjAmount.ZERO,
            port.canExtract() ? UNKNOWN_RATE : MjAmount.ZERO
        );
    }

    /** The same legacy MJ capability roles and extraction guarantees as NeoForge. */
    private record LegacyPort(
        IMjConnector connector, IMjReceiver receiver, IMjRedstoneReceiver redstone,
        IMjReadable readable, IMjPassiveProvider provider
    ) implements MjDemandPort, MjPassivePort {
        public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            IMjReceiver target = receiver != null ? receiver : redstone;
            if (target == null || !target.canReceive() || offered.isZero())
                return MjTransferResult.none(offered);
            long remainder = target.receivePower(offered.microMj(),
                mode == OperationMode.EXECUTE ? FluidAction.EXECUTE : FluidAction.SIMULATE);
            remainder = Math.max(0L, Math.min(offered.microMj(), remainder));
            return MjTransferResult.of(offered, MjAmount.ofMicro(offered.microMj() - remainder));
        }

        public MjTransferResult extract(MjAmount requested, OperationMode mode) {
            return extract(MjAmount.ZERO, requested, mode);
        }

        public MjTransferResult extract(MjAmount minimum, MjAmount maximum, OperationMode mode) {
            if (minimum.compareTo(maximum) > 0)
                throw new IllegalArgumentException("minimum exceeds maximum");
            if (provider == null || maximum.isZero()) return MjTransferResult.none(maximum);
            long moved = provider.extractPower(minimum.microMj(), maximum.microMj(),
                mode == OperationMode.EXECUTE);
            if ((moved != 0L && moved < minimum.microMj()) || moved > maximum.microMj() || moved < 0L)
                throw new IllegalStateException("Legacy MJ provider violated extraction contract");
            return MjTransferResult.of(maximum, MjAmount.ofMicro(moved));
        }

        public MjAmount requested() {
            IMjReceiver target = receiver != null ? receiver : redstone;
            return MjAmount.ofMicro(target == null || !target.canReceive() ? 0L
                : Math.max(0L, target.getPowerRequested()));
        }

        public MjAmount stored() {
            return MjAmount.ofMicro(readable == null ? 0L : Math.max(0L, readable.getStored()));
        }

        public MjAmount capacity() {
            return MjAmount.ofMicro(readable == null ? 0L : Math.max(0L, readable.getCapacity()));
        }

        public boolean canInsert() {
            IMjReceiver target = receiver != null ? receiver : redstone;
            return target != null && target.canReceive();
        }

        public boolean canExtract() { return provider != null; }

        private MjPortDescriptor descriptor() {
            EnumSet<MjPortRole> roles = EnumSet.noneOf(MjPortRole.class);
            if (connector != null) roles.add(MjPortRole.CONNECTOR);
            if (receiver != null || redstone != null) roles.add(MjPortRole.CONSUMER);
            if (redstone != null) roles.add(MjPortRole.REDSTONE_RECEIVER);
            if (readable != null) roles.add(MjPortRole.READABLE);
            if (provider != null) roles.add(MjPortRole.PASSIVE_PROVIDER);
            return new MjPortDescriptor(NETWORK_ID, roles,
                receiver == null && redstone == null ? MjAmount.ZERO : UNKNOWN_RATE,
                provider == null ? MjAmount.ZERO : UNKNOWN_RATE);
        }
    }

    private record FePort(EnergyStorage storage) implements MjDemandPort, MjPassivePort {
        private static long ratio() {
            return BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
        }

        private static MjAmount converted(long external, long ratio) {
            long nonnegative = Math.max(0L, external);
            return MjAmount.ofMicro(nonnegative > Long.MAX_VALUE / ratio
                ? Long.MAX_VALUE : nonnegative * ratio);
        }

        public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            if (!storage.supportsInsertion() || offered.isZero()) return MjTransferResult.none(offered);
            long r = ratio(), fe = offered.microMj() / r;
            if (fe <= 0) return MjTransferResult.none(offered);
            try (Transaction transaction = FabricTransactionScope.begin()) {
                long moved = Math.max(0L, Math.min(fe, storage.insert(fe, transaction)));
                if (mode == OperationMode.EXECUTE && moved > 0) transaction.commit();
                return MjTransferResult.of(offered, converted(moved, r));
            }
        }

        public MjTransferResult extract(MjAmount requested, OperationMode mode) {
            return extract(MjAmount.ZERO, requested, mode);
        }

        public MjTransferResult extract(MjAmount minimum, MjAmount maximum, OperationMode mode) {
            if (minimum.compareTo(maximum) > 0) {
                throw new IllegalArgumentException("minimum exceeds maximum");
            }
            if (!storage.supportsExtraction() || maximum.isZero()) return MjTransferResult.none(maximum);
            long r = ratio();
            long minFe = minimum.microMj() / r + (minimum.microMj() % r == 0 ? 0 : 1);
            long maxFe = maximum.microMj() / r;
            if (minFe > maxFe) return MjTransferResult.none(maximum);
            try (Transaction transaction = FabricTransactionScope.begin()) {
                long preview;
                try (Transaction probe = transaction.openNested()) {
                    preview = Math.max(0L, Math.min(maxFe, storage.extract(maxFe, probe)));
                }
                if (preview < minFe) return MjTransferResult.none(maximum);
                long moved = Math.max(0L, Math.min(preview, storage.extract(preview, transaction)));
                if (moved < minFe) return MjTransferResult.none(maximum);
                if (mode == OperationMode.EXECUTE && moved > 0) transaction.commit();
                return MjTransferResult.of(maximum, converted(moved, r));
            }
        }

        public MjAmount requested() {
            if (!storage.supportsInsertion()) return MjAmount.ZERO;
            long room = Math.max(0L, storage.getCapacity() - Math.max(0L, storage.getAmount()));
            if (room == 0 && storage.getCapacity() == 0) {
                try (Transaction probe = FabricTransactionScope.begin()) {
                    room = Math.max(0L, storage.insert(1_000_000L, probe));
                }
            }
            return converted(room, ratio());
        }

        public MjAmount stored() { return converted(storage.getAmount(), ratio()); }
        public MjAmount capacity() { return converted(storage.getCapacity(), ratio()); }
        public boolean canInsert() { return storage.supportsInsertion(); }
        public boolean canExtract() { return storage.supportsExtraction(); }

        private MjPortDescriptor descriptor() {
            EnumSet<MjPortRole> roles = EnumSet.of(MjPortRole.CONNECTOR, MjPortRole.READABLE);
            if (canInsert()) roles.add(MjPortRole.CONSUMER);
            if (canExtract()) {
                roles.add(MjPortRole.PROVIDER);
                roles.add(MjPortRole.PASSIVE_PROVIDER);
            }
            // Never read mutable storage or simulate during client-side topology discovery.
            return new MjPortDescriptor(
                NETWORK_ID,
                roles,
                canInsert() ? UNKNOWN_RATE : MjAmount.ZERO,
                canExtract() ? UNKNOWN_RATE : MjAmount.ZERO
            );
        }
    }
}
