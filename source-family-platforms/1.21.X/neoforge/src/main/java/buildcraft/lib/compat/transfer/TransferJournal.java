//? source if >=1.21.11
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.compat.transfer;

import java.util.function.Consumer;
import java.util.function.Supplier;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Transaction participation belongs to the backing storage, NOT a capability wrapper.
 * Thus aliases (two pipe faces, combined inventories, joined tanks and MJ/FE views)
 * share one journal and nested rollback restores the same physical resource exactly once.
 */
public final class TransferJournal<S> extends SnapshotJournal<S> {
    private final Supplier<S> capture;
    private final Consumer<S> restore;
    private final Consumer<S> committed;

    public TransferJournal(Supplier<S> capture, Consumer<S> restore, Consumer<S> committed) {
        this.capture = capture;
        this.restore = restore;
        this.committed = committed;
    }

    public void record() {
        TransactionContext transaction = current();
        if (transaction != null) updateSnapshots(transaction);
    }

    @Override protected S createSnapshot() { return capture.get(); }
    @Override protected void revertToSnapshot(S state) { restore.accept(state); }
    @Override protected void onRootCommit(S originalState) { committed.accept(originalState); }

    /** Adapts legacy BC handlers without transaction arguments to the NeoForge transaction journal. */
    @SuppressWarnings("deprecation")
    public static TransactionContext current() {
        return Transaction.getCurrentOpenedTransaction();
    }

    public static boolean active() { return current() != null; }

    /** Defer irreversible notifications, not resource transfers, until the ROOT transaction commits. */
    public static boolean defer(Runnable notification) {
        TransactionContext transaction = current();
        if (transaction == null) return false;
        new SnapshotJournal<Boolean>() {
            @Override protected Boolean createSnapshot() { return Boolean.TRUE; }
            @Override protected void revertToSnapshot(Boolean ignored) {}
            @Override protected void onRootCommit(Boolean ignored) { notification.run(); }
        }.updateSnapshots(transaction);
        return true;
    }

    public static void notifyAfterCommit(Runnable notification) {
        if (!defer(notification)) notification.run();
    }
}
