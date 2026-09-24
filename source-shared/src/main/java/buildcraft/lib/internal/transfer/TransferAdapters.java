package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.lib.platform.storage.ItemStorage;
import java.util.Objects;
import net.minecraft.world.item.ItemStack;

/**
 * Shared operation adapters between BC internal storage, API2 ports and loader-native lookup bridges.
 * Native classes never cross this file; loaders only supply a {@link FluidCarrier} where required.
 */
public final class TransferAdapters {
    private TransferAdapters() {}

    public static ItemTransferAccess items(ItemStorage storage) {
        return new SlottedItems(Objects.requireNonNull(storage, "storage"));
    }

    public static ItemTransferAccess items(ItemPort port) {
        Objects.requireNonNull(port, "port");
        if (port instanceof ApiItemPort wrapped) return wrapped.access;
        return new PortItems(port);
    }

    public static ItemPort itemPort(ItemTransferAccess access) {
        Objects.requireNonNull(access, "access");
        if (access instanceof PortItems wrapped) return wrapped.port;
        return new ApiItemPort(access);
    }

    public static <F> FluidTransferAccess fluids(
        FluidStorage<F> storage,
        FluidCarrier<F> carrier,
        FluidMatchContext matchContext
    ) {
        return new SlottedFluids<>(
            Objects.requireNonNull(storage, "storage"),
            Objects.requireNonNull(carrier, "carrier"),
            Objects.requireNonNull(matchContext, "matchContext")
        );
    }

    public static FluidTransferAccess fluids(FluidPort port) {
        Objects.requireNonNull(port, "port");
        if (port instanceof ApiFluidPort wrapped) return wrapped.access;
        return new PortFluids(port);
    }

    public static FluidPort fluidPort(FluidTransferAccess access) {
        Objects.requireNonNull(access, "access");
        if (access instanceof PortFluids wrapped) return wrapped.port;
        return new ApiFluidPort(access);
    }

    public static EnergyTransferAccess energy(EnergyStorage storage) {
        return new NativeEnergy(Objects.requireNonNull(storage, "storage"));
    }

    public static EnergyTransferAccess energy(ExternalEnergyPort port) {
        Objects.requireNonNull(port, "port");
        if (port instanceof ApiEnergyPort wrapped) return wrapped.access;
        return new PortEnergy(port);
    }

    public static ExternalEnergyPort energyPort(EnergyTransferAccess access) {
        Objects.requireNonNull(access, "access");
        if (access instanceof PortEnergy wrapped) return wrapped.port;
        return new ApiEnergyPort(access);
    }

    private static final class SlottedItems implements ItemTransferAccess {
        private final ItemStorage storage;
        SlottedItems(ItemStorage storage) { this.storage = storage; }

        @Override
        public ItemTransferResult insert(ItemStack offered, OperationScope scope) {
            if (offered == null || offered.isEmpty()) {
                return ItemTransferResult.nothing(offered == null ? 0 : offered.getCount());
            }
            try (OperationScope.Guard guard = scope.enter(storage)) {
                if (!guard.entered()) return ItemTransferResult.nothing(offered.getCount());
                ItemStack remainder = offered.copy();
                for (int slot = 0; slot < storage.getSlots() && !remainder.isEmpty(); slot++) {
                    remainder = storage.insertItem(slot, remainder, scope.simulate());
                }
                return ItemTransferResult.ofInsertion(offered, offered.getCount() - remainder.getCount());
            }
        }

        @Override
        public ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationScope scope) {
            if (matcher == null || maxCount <= 0) return ItemTransferResult.nothing(Math.max(0, maxCount));
            try (OperationScope.Guard guard = scope.enter(storage)) {
                if (!guard.entered()) return ItemTransferResult.nothing(maxCount);
                for (int slot = 0; slot < storage.getSlots(); slot++) {
                    ItemStack stored = storage.getStackInSlot(slot);
                    if (stored == null || stored.isEmpty() || !matcher.matches(stored)) continue;
                    ItemStack extracted = storage.extractItem(slot, maxCount, scope.simulate());
                    if (extracted != null && !extracted.isEmpty()) {
                        return ItemTransferResult.ofExtraction(maxCount, extracted);
                    }
                }
                return ItemTransferResult.nothing(maxCount);
            }
        }
    }

    private static final class PortItems implements ItemTransferAccess {
        private final ItemPort port;
        PortItems(ItemPort port) { this.port = port; }
        @Override public ItemTransferResult insert(ItemStack offered, OperationScope scope) {
            int requested = offered == null ? 0 : offered.getCount();
            if (offered == null || offered.isEmpty()) return ItemTransferResult.nothing(requested);
            try (OperationScope.Guard guard = scope.enter(port)) {
                return guard.entered() ? port.insert(offered, scope.mode()) : ItemTransferResult.nothing(requested);
            }
        }
        @Override public ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationScope scope) {
            try (OperationScope.Guard guard = scope.enter(port)) {
                return guard.entered() ? port.extract(matcher, maxCount, scope.mode()) : ItemTransferResult.nothing(Math.max(0, maxCount));
            }
        }
    }

    private static final class ApiItemPort implements ItemPort {
        private final ItemTransferAccess access;
        ApiItemPort(ItemTransferAccess access) { this.access = access; }
        @Override public ItemTransferResult insert(ItemStack offered, OperationMode mode) {
            try (OperationScope scope = OperationScope.open(mode)) { return access.insert(offered, scope); }
        }
        @Override public ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationMode mode) {
            try (OperationScope scope = OperationScope.open(mode)) { return access.extract(matcher, maxCount, scope); }
        }
    }

    private static final class SlottedFluids<F> implements FluidTransferAccess {
        private final FluidStorage<F> storage;
        private final FluidCarrier<F> carrier;
        private final FluidMatchContext matchContext;
        SlottedFluids(FluidStorage<F> storage, FluidCarrier<F> carrier, FluidMatchContext matchContext) {
            this.storage = storage;
            this.carrier = carrier;
            this.matchContext = matchContext;
        }
        @Override public FluidTransferResult insert(FluidVolume offered, OperationScope scope) {
            if (offered == null || offered.isEmpty()) {
                return FluidTransferResult.nothing(offered == null ? FluidAmount.ZERO : offered.amount());
            }
            try (OperationScope.Guard guard = scope.enter(storage)) {
                if (!guard.entered()) return FluidTransferResult.nothing(offered.amount());
                F nativeStack = carrier.fromVolume(offered);
                if (nativeStack == null || carrier.isEmpty(nativeStack)) return FluidTransferResult.nothing(offered.amount());
                int accepted = storage.fill(nativeStack, scope.simulate());
                return FluidTransferResult.ofInsertion(offered, FluidAmount.of(Math.max(0, accepted)));
            }
        }
        @Override public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationScope scope) {
            if (matcher == null || maxAmount == null || maxAmount.isZero()) {
                return FluidTransferResult.nothing(maxAmount == null ? FluidAmount.ZERO : maxAmount);
            }
            int limit = (int) Math.min(Integer.MAX_VALUE, maxAmount.milliBuckets());
            try (OperationScope.Guard guard = scope.enter(storage)) {
                if (!guard.entered()) return FluidTransferResult.nothing(maxAmount);
                for (int tank = 0; tank < storage.getTanks(); tank++) {
                    F stored = storage.getFluidInTank(tank);
                    if (stored == null || carrier.isEmpty(stored)) continue;
                    FluidVolume storedVolume = carrier.toVolume(stored);
                    if (storedVolume.isEmpty() || !matcher.matches(storedVolume.requireVariant(), matchContext)) continue;
                    int requested = Math.min(limit, carrier.amount(stored));
                    F request = carrier.copyWithAmount(stored, requested);
                    F drained = storage.drain(request, scope.simulate());
                    FluidVolume result = drained == null ? FluidVolume.empty() : carrier.toVolume(drained);
                    return FluidTransferResult.ofExtraction(maxAmount, result);
                }
                return FluidTransferResult.nothing(maxAmount);
            }
        }
    }

    private static final class PortFluids implements FluidTransferAccess {
        private final FluidPort port;
        PortFluids(FluidPort port) { this.port = port; }
        @Override public FluidTransferResult insert(FluidVolume offered, OperationScope scope) {
            FluidAmount requested = offered == null ? FluidAmount.ZERO : offered.amount();
            if (offered == null || offered.isEmpty()) return FluidTransferResult.nothing(requested);
            try (OperationScope.Guard guard = scope.enter(port)) {
                return guard.entered() ? port.insert(offered, scope.mode()) : FluidTransferResult.nothing(requested);
            }
        }
        @Override public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationScope scope) {
            FluidAmount requested = maxAmount == null ? FluidAmount.ZERO : maxAmount;
            try (OperationScope.Guard guard = scope.enter(port)) {
                return guard.entered() ? port.extract(matcher, maxAmount, scope.mode()) : FluidTransferResult.nothing(requested);
            }
        }
    }

    private static final class ApiFluidPort implements FluidPort {
        private final FluidTransferAccess access;
        ApiFluidPort(FluidTransferAccess access) { this.access = access; }
        @Override public FluidTransferResult insert(FluidVolume offered, OperationMode mode) {
            try (OperationScope scope = OperationScope.open(mode)) { return access.insert(offered, scope); }
        }
        @Override public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationMode mode) {
            try (OperationScope scope = OperationScope.open(mode)) { return access.extract(matcher, maxAmount, scope); }
        }
    }

    private static final class NativeEnergy implements EnergyTransferAccess {
        private final EnergyStorage storage;
        NativeEnergy(EnergyStorage storage) { this.storage = storage; }
        @Override public long insert(long offered, OperationScope scope) {
            int request = clampExternalEnergy(offered);
            try (OperationScope.Guard guard = scope.enter(storage)) {
                return guard.entered() ? storage.receiveEnergy(request, scope.simulate()) : 0;
            }
        }
        @Override public long extract(long requested, OperationScope scope) {
            int request = clampExternalEnergy(requested);
            try (OperationScope.Guard guard = scope.enter(storage)) {
                return guard.entered() ? storage.extractEnergy(request, scope.simulate()) : 0;
            }
        }
        @Override public long stored() { return storage.getEnergyStored(); }
        @Override public long capacity() { return storage.getMaxEnergyStored(); }
        @Override public boolean canInsert() { return storage.canReceive(); }
        @Override public boolean canExtract() { return storage.canExtract(); }
    }

    private static final class PortEnergy implements EnergyTransferAccess {
        private final ExternalEnergyPort port;
        PortEnergy(ExternalEnergyPort port) { this.port = port; }
        @Override public long insert(long offered, OperationScope scope) {
            try (OperationScope.Guard guard = scope.enter(port)) { return guard.entered() ? port.insert(offered, scope.mode()) : 0; }
        }
        @Override public long extract(long requested, OperationScope scope) {
            try (OperationScope.Guard guard = scope.enter(port)) { return guard.entered() ? port.extract(requested, scope.mode()) : 0; }
        }
        @Override public long stored() { return port.stored(); }
        @Override public long capacity() { return port.capacity(); }
        @Override public boolean canInsert() { return port.canInsert(); }
        @Override public boolean canExtract() { return port.canExtract(); }
    }

    private static final class ApiEnergyPort implements ExternalEnergyPort {
        private final EnergyTransferAccess access;
        ApiEnergyPort(EnergyTransferAccess access) { this.access = access; }
        @Override public long insert(long offered, OperationMode mode) {
            try (OperationScope scope = OperationScope.open(mode)) { return access.insert(offered, scope); }
        }
        @Override public long extract(long requested, OperationMode mode) {
            try (OperationScope scope = OperationScope.open(mode)) { return access.extract(requested, scope); }
        }
        @Override public long stored() { return access.stored(); }
        @Override public long capacity() { return access.capacity(); }
        @Override public boolean canInsert() { return access.canInsert(); }
        @Override public boolean canExtract() { return access.canExtract(); }
    }

    private static int clampExternalEnergy(long amount) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, amount));
    }
}
