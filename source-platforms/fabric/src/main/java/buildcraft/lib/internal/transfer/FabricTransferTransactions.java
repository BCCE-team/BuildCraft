package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.OperationMode;
import java.util.Objects;
import java.util.function.Function;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/**
 * Owns the native Fabric transaction for one BuildCraft {@link OperationScope}.
 *
 * <p>Every item/fluid/energy adapter participating in the same logical operation must reuse this transaction.
 * A nested simulation under an executing root receives an aborted nested transaction so probes never leak into
 * the final commit.</p>
 */
public final class FabricTransferTransactions {
    private static final Object TRANSACTION_KEY = new Object();

    private FabricTransferTransactions() {
    }

    public static <T> T with(OperationScope scope, Function<TransactionContext, T> action) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(action, "action");

        Transaction root = scope.sharedAttachment(TRANSACTION_KEY, () -> {
            Transaction transaction = Transaction.openOuter();
            scope.onRootClose(commit -> {
                if (commit) transaction.commit();
                else transaction.abort();
            });
            return transaction;
        });

        if (scope.simulate() && scope.rootMode() == OperationMode.EXECUTE) {
            try (Transaction nested = Transaction.openNested(root)) {
                return guarded(scope, action, nested);
            }
        }
        return guarded(scope, action, root);
    }

    /** Runs a reversible native probe beneath an already-owned transaction. */
    public static <T> T probe(OperationScope scope, TransactionContext parent, Function<TransactionContext, T> action) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(action, "action");
        try (Transaction nested = Transaction.openNested(parent)) {
            return guarded(scope, action, nested);
        }
    }

    private static <T> T guarded(
        OperationScope scope,
        Function<TransactionContext, T> action,
        TransactionContext transaction
    ) {
        try {
            return action.apply(transaction);
        } catch (RuntimeException | Error failure) {
            scope.markFailed();
            throw failure;
        }
    }
}
