package buildcraft.lib.internal.mj;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.EnergyConversion;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjConnectionContext;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjPortDescriptor;
import buildcraft.api.v2.energy.MjPortRole;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.lib.internal.api.v2.energy.MjRuntimeLookup;
import buildcraft.lib.internal.transfer.EnergyTransferAccess;
import buildcraft.lib.internal.transfer.OperationScope;
import buildcraft.lib.platform.storage.PlatformStorage;
import java.util.EnumSet;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Fabric MJ bridge for Team Reborn Energy API endpoints.
 *
 * <p>The external-energy unit stays on the {@link EnergyTransferAccess} boundary. MJ conversion is exact and floors
 * sub-unit MJ remainders, so conversion can never create energy. Native Fabric transactions are owned by the
 * {@link OperationScope} used for each public MJ operation.</p>
 */
public final class MjApi2PlatformBridge {
    private static final ResourceLocation NETWORK_ID = new ResourceLocation("buildcraft", "mj");
    private static final MjAmount UNKNOWN_RATE = MjAmount.ofMicro(Long.MAX_VALUE);

    private MjApi2PlatformBridge() { }

    public static void install() {
        MjRuntimeLookup.install(new MjRuntimeLookup.Backend() {
            @Override
            public Optional<MjPort> port(Level level, BlockPos pos, Direction side) {
                ExternalPort port = endpoint(level, pos, side);
                return port == null ? Optional.empty() : Optional.of(port);
            }

            @Override
            public Optional<MjPortDescriptor> descriptor(Level level, BlockPos pos, Direction side) {
                ExternalPort port = endpoint(level, pos, side);
                return port == null ? Optional.empty() : Optional.of(port.descriptor());
            }

            @Override
            public boolean canConnect(MjConnectionContext context) {
                return endpoint(context.level(), context.position(), context.side()) != null
                    || endpoint(
                        context.level(),
                        context.position().relative(context.side()),
                        context.side().getOpposite()
                    ) != null;
            }
        });
    }

    private static ExternalPort endpoint(Level level, BlockPos pos, Direction side) {
        if (!BuildCraftApi.service(BuildCraftServices.ENERGY).automaticFeConversionEnabled()) return null;
        EnergyTransferAccess storage = PlatformStorage.energyTransfer(level, pos, side);
        return storage == null || (!storage.canInsert() && !storage.canExtract()) ? null : new ExternalPort(storage);
    }

    private static final class ExternalPort implements MjPort {
        private final EnergyTransferAccess storage;

        private ExternalPort(EnergyTransferAccess storage) {
            this.storage = storage;
        }

        @Override
        public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            if (!storage.canInsert() || offered.isZero()) return MjTransferResult.none(offered);
            EnergyConversion conversion = conversion();
            long external = conversion.microMjToWholeFe(offered.microMj());
            if (external <= 0) return MjTransferResult.none(offered);

            long accepted;
            try (OperationScope scope = OperationScope.open(mode)) {
                accepted = clampMoved(storage.insert(external, scope), external);
            }
            return MjTransferResult.of(offered, toMjExact(accepted, conversion));
        }

        @Override
        public MjTransferResult extract(MjAmount requested, OperationMode mode) {
            if (!storage.canExtract() || requested.isZero()) return MjTransferResult.none(requested);
            EnergyConversion conversion = conversion();
            long external = conversion.microMjToWholeFe(requested.microMj());
            if (external <= 0) return MjTransferResult.none(requested);

            long moved;
            try (OperationScope scope = OperationScope.open(mode)) {
                moved = clampMoved(storage.extract(external, scope), external);
            }
            return MjTransferResult.of(requested, toMjExact(moved, conversion));
        }

        @Override
        public MjAmount stored() {
            return toMjSaturating(storage.stored(), conversion());
        }

        @Override
        public MjAmount capacity() {
            return toMjSaturating(storage.capacity(), conversion());
        }

        @Override
        public boolean canInsert() {
            return storage.canInsert();
        }

        @Override
        public boolean canExtract() {
            return storage.canExtract();
        }

        private MjPortDescriptor descriptor() {
            EnumSet<MjPortRole> roles = EnumSet.of(MjPortRole.CONNECTOR, MjPortRole.READABLE);
            if (canInsert()) roles.add(MjPortRole.CONSUMER);
            if (canExtract()) roles.add(MjPortRole.PROVIDER);
            return new MjPortDescriptor(
                NETWORK_ID,
                roles,
                canInsert() ? UNKNOWN_RATE : MjAmount.ZERO,
                canExtract() ? UNKNOWN_RATE : MjAmount.ZERO
            );
        }
    }

    private static EnergyConversion conversion() {
        return BuildCraftApi.service(BuildCraftServices.ENERGY).conversion();
    }

    private static MjAmount toMjExact(long external, EnergyConversion conversion) {
        if (external <= 0) return MjAmount.ZERO;
        return MjAmount.ofMicro(conversion.feToMicroMj(external));
    }

    private static MjAmount toMjSaturating(long external, EnergyConversion conversion) {
        if (external <= 0) return MjAmount.ZERO;
        long ratio = conversion.microMjPerFe();
        if (external > Long.MAX_VALUE / ratio) return MjAmount.ofMicro(Long.MAX_VALUE);
        return MjAmount.ofMicro(external * ratio);
    }

    private static long clampMoved(long moved, long requested) {
        if (moved <= 0) return 0;
        return Math.min(moved, requested);
    }
}
