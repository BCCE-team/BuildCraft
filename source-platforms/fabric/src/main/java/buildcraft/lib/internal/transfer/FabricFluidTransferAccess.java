package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;
import java.util.Objects;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/** Slotless, transaction-native adapter for arbitrary Fabric fluid storages. */
public final class FabricFluidTransferAccess implements FluidTransferAccess {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;

    private final Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage;

    public FabricFluidTransferAccess(Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public FluidTransferResult insert(FluidVolume offered, OperationScope scope) {
        if (offered == null || offered.isEmpty()) {
            return FluidTransferResult.nothing(offered == null ? FluidAmount.ZERO : offered.amount());
        }
        Objects.requireNonNull(scope, "scope");
        var nativeVariant = FabricFluidVariants.fromApi(offered.requireVariant()).orElse(null);
        if (nativeVariant == null) return FluidTransferResult.nothing(offered.amount());

        try (OperationScope.Guard guard = scope.enter(storage)) {
            if (!guard.entered()) return FluidTransferResult.nothing(offered.amount());
            long acceptedMb = FabricTransferTransactions.with(scope, transaction ->
                alignedInsert(scope, nativeVariant, offered.amount().milliBuckets(), transaction)
            );
            return FluidTransferResult.ofInsertion(offered, FluidAmount.of(acceptedMb));
        }
    }

    @Override
    public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationScope scope) {
        if (matcher == null || maxAmount == null || maxAmount.isZero()) {
            return FluidTransferResult.nothing(maxAmount == null ? FluidAmount.ZERO : maxAmount);
        }
        Objects.requireNonNull(scope, "scope");

        try (OperationScope.Guard guard = scope.enter(storage)) {
            if (!guard.entered()) return FluidTransferResult.nothing(maxAmount);
            return FabricTransferTransactions.with(scope, transaction -> {
                for (StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view : storage) {
                    if (view.isResourceBlank() || view.getAmount() < DROPLETS_PER_MB) continue;
                    net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant nativeVariant = view.getResource();
                    buildcraft.api.v2.fluid.FluidVariant apiVariant = FabricFluidVariants.toApi(nativeVariant);
                    if (!matcher.matches(apiVariant, FabricFluidVariants.MATCH_CONTEXT)) continue;

                    long requestedMb = Math.min(maxAmount.milliBuckets(), dropletsToMb(view.getAmount()));
                    long extractedMb = alignedExtract(scope, nativeVariant, requestedMb, transaction);
                    if (extractedMb > 0) {
                        return FluidTransferResult.ofExtraction(
                            maxAmount,
                            FluidVolume.of(apiVariant, FluidAmount.of(extractedMb))
                        );
                    }
                }
                return FluidTransferResult.nothing(maxAmount);
            });
        }
    }

    private long alignedInsert(
        OperationScope scope,
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant variant,
        long requestedMb,
        TransactionContext transaction
    ) {
        long requestedDroplets = mbToDroplets(requestedMb);
        if (requestedDroplets <= 0) return 0;
        long probed = FabricTransferTransactions.probe(
            scope, transaction, nested -> storage.insert(variant, requestedDroplets, nested)
        );
        long aligned = alignDroplets(probed);
        if (aligned <= 0) return 0;
        long executed = storage.insert(variant, aligned, transaction);
        requireExact(scope, "insert", aligned, executed);
        return dropletsToMb(aligned);
    }

    private long alignedExtract(
        OperationScope scope,
        net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant variant,
        long requestedMb,
        TransactionContext transaction
    ) {
        long requestedDroplets = mbToDroplets(requestedMb);
        if (requestedDroplets <= 0) return 0;
        long probed = FabricTransferTransactions.probe(
            scope, transaction, nested -> storage.extract(variant, requestedDroplets, nested)
        );
        long aligned = alignDroplets(probed);
        if (aligned <= 0) return 0;
        long executed = storage.extract(variant, aligned, transaction);
        requireExact(scope, "extract", aligned, executed);
        return dropletsToMb(aligned);
    }

    private static void requireExact(OperationScope scope, String operation, long expected, long actual) {
        if (actual == expected) return;
        scope.markFailed();
        throw new IllegalStateException(
            "Fabric fluid storage changed between " + operation + " probe and execute: expected "
                + expected + " droplets, got " + actual
        );
    }

    private static long alignDroplets(long droplets) {
        return Math.max(0L, droplets) / DROPLETS_PER_MB * DROPLETS_PER_MB;
    }

    private static long dropletsToMb(long droplets) {
        return Math.max(0L, droplets) / DROPLETS_PER_MB;
    }

    private static long mbToDroplets(long milliBuckets) {
        if (milliBuckets <= 0) return 0;
        long maxMb = Long.MAX_VALUE / DROPLETS_PER_MB;
        return Math.min(milliBuckets, maxMb) * DROPLETS_PER_MB;
    }
}
