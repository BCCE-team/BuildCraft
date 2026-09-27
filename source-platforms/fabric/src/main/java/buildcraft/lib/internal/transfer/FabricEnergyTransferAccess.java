package buildcraft.lib.internal.transfer;
// Loader boundary owner: net.fabricmc / Team Reborn Energy API.

import java.util.Objects;

/**
 * Transaction-native adapter for Team Reborn Energy API endpoints.
 *
 * <p>The external energy unit is kept unchanged here. MJ conversion belongs to the MJ compatibility bridge;
 * this class only guarantees that an external-energy operation participates in the same native Fabric transaction
 * as the rest of the owning {@link OperationScope}.</p>
 */
public final class FabricEnergyTransferAccess implements EnergyTransferAccess {
    private final team.reborn.energy.api.EnergyStorage storage;

    public FabricEnergyTransferAccess(team.reborn.energy.api.EnergyStorage storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public long insert(long offered, OperationScope scope) {
        if (offered <= 0 || !storage.supportsInsertion()) return 0;
        Objects.requireNonNull(scope, "scope");
        try (OperationScope.Guard guard = scope.enter(storage)) {
            if (!guard.entered()) return 0;
            return FabricTransferTransactions.with(scope, transaction ->
                clampTransfer(storage.insert(offered, transaction), offered)
            );
        }
    }

    @Override
    public long extract(long requested, OperationScope scope) {
        if (requested <= 0 || !storage.supportsExtraction()) return 0;
        Objects.requireNonNull(scope, "scope");
        try (OperationScope.Guard guard = scope.enter(storage)) {
            if (!guard.entered()) return 0;
            return FabricTransferTransactions.with(scope, transaction ->
                clampTransfer(storage.extract(requested, transaction), requested)
            );
        }
    }

    @Override
    public long stored() {
        return Math.max(0L, storage.getAmount());
    }

    @Override
    public long capacity() {
        return Math.max(0L, storage.getCapacity());
    }

    @Override
    public boolean canInsert() {
        return storage.supportsInsertion();
    }

    @Override
    public boolean canExtract() {
        return storage.supportsExtraction();
    }

    public team.reborn.energy.api.EnergyStorage nativeStorage() {
        return storage;
    }

    private static long clampTransfer(long moved, long requested) {
        if (moved <= 0) return 0;
        return Math.min(moved, requested);
    }
}
