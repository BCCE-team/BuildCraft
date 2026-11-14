package buildcraft.lib.internal.transfer;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

/**
 * Every API v2 operation owns its commit boundary. If invoked by another
 * Fabric storage transaction, the operation becomes a nested savepoint.
 * Simulation closes without committing, even if the parent later commits.
 */
public final class FabricTransactionScope {
    private FabricTransactionScope() {}

    @SuppressWarnings("deprecation")
    public static Transaction begin() {
        TransactionContext parent = Transaction.getCurrentUnsafe();
        return Transaction.openNested(parent);
    }
}
