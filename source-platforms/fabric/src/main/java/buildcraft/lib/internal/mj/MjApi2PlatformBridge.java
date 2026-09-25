package buildcraft.lib.internal.mj;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjConnectionContext;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjPortDescriptor;
import buildcraft.api.v2.energy.MjPortRole;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.lib.internal.api.v2.energy.MjRuntimeLookup;
import java.util.EnumSet;
import java.util.Optional;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import team.reborn.energy.api.EnergyStorage;

/** Fabric energy bridge for external Energy API endpoints. Native BuildCraft MJ endpoints are wired in gameplay parity. */
public final class MjApi2PlatformBridge {
    private static final ResourceLocation NETWORK_ID = new ResourceLocation("buildcraft", "mj");
    private static final MjAmount UNKNOWN_RATE = MjAmount.ofMicro(Long.MAX_VALUE);
    private MjApi2PlatformBridge() { }

    public static void install() {
        MjRuntimeLookup.install(new MjRuntimeLookup.Backend() {
            @Override public Optional<MjPort> port(Level level, BlockPos pos, Direction side) {
                FePort port = endpoint(level, pos, side);
                return port == null ? Optional.empty() : Optional.of(port);
            }
            @Override public Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side) {
                FePort port = endpoint(level, pos, side);
                return port == null ? Optional.empty() : Optional.of(port.descriptor());
            }
            @Override public boolean canConnect(MjConnectionContext context) {
                return endpoint(context.level(), context.position(), context.side()) != null
                    || endpoint(context.level(), context.position().relative(context.side()), context.side().getOpposite()) != null;
            }
        });
    }

    private static FePort endpoint(Level level, BlockPos pos, Direction side) {
        if (!BuildCraftApi.service(BuildCraftServices.ENERGY).automaticFeConversionEnabled()) return null;
        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        EnergyStorage storage = EnergyStorage.SIDED.find(level, pos, state, blockEntity, side);
        return storage == null || (!storage.supportsInsertion() && !storage.supportsExtraction()) ? null : new FePort(storage);
    }

    private static final class FePort implements MjPort {
        private final EnergyStorage storage;
        private FePort(EnergyStorage storage) { this.storage = storage; }
        @Override public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            if (!storage.supportsInsertion() || offered.isZero()) return MjTransferResult.none(offered);
            long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
            long fe = offered.microMj() / ratio;
            if (fe <= 0) return MjTransferResult.none(offered);
            try (Transaction tx = Transaction.openOuter()) {
                long accepted = storage.insert(fe, tx);
                if (mode == OperationMode.EXECUTE) tx.commit();
                return MjTransferResult.of(offered, MjAmount.ofMicro(Math.max(0, accepted) * ratio));
            }
        }
        @Override public MjTransferResult extract(MjAmount requested, OperationMode mode) {
            if (!storage.supportsExtraction() || requested.isZero()) return MjTransferResult.none(requested);
            long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
            long fe = requested.microMj() / ratio;
            if (fe <= 0) return MjTransferResult.none(requested);
            try (Transaction tx = Transaction.openOuter()) {
                long moved = storage.extract(fe, tx);
                if (mode == OperationMode.EXECUTE) tx.commit();
                return MjTransferResult.of(requested, MjAmount.ofMicro(Math.max(0, moved) * ratio));
            }
        }
        @Override public MjAmount stored() {
            return MjAmount.ofMicro(BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().feToMicroMj(Math.max(0, storage.getAmount())));
        }
        @Override public MjAmount capacity() {
            return MjAmount.ofMicro(BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().feToMicroMj(Math.max(0, storage.getCapacity())));
        }
        @Override public boolean canInsert() { return storage.supportsInsertion(); }
        @Override public boolean canExtract() { return storage.supportsExtraction(); }
        private MjPortDescriptor descriptor() {
            EnumSet<MjPortRole> roles = EnumSet.of(MjPortRole.CONNECTOR, MjPortRole.READABLE);
            if (canInsert()) roles.add(MjPortRole.CONSUMER);
            if (canExtract()) roles.add(MjPortRole.PROVIDER);
            return new MjPortDescriptor(NETWORK_ID, roles, canInsert() ? UNKNOWN_RATE : MjAmount.ZERO,
                canExtract() ? UNKNOWN_RATE : MjAmount.ZERO);
        }
    }
}
