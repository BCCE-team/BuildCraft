/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.compat.transfer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/**
 * Fabric 26.3 snapshot journal for the existing BCCE transaction contract.
 *
 * One physical storage owns this journal, regardless of how many views or faces
 * expose it. Fabric is responsible for nested savepoints and restores the
 * previous state on every abort. Notifications run only after the outermost
 * transaction commits. No snapshot is made outside a native transaction.
 */
public final class TransferJournal<S> extends SnapshotParticipant<S> {
    private final Supplier<S> capture;
    private final Consumer<S> restore;
    private final Consumer<S> committed;

    /**
     * Original value from the first snapshot under an outer transaction.
     * Not shared across transactions; access is confined to the owning game thread.
     */
    private S originalRootState;
    private boolean rootSnapshotCaptured;

    public TransferJournal(Supplier<S> capture, Consumer<S> restore, Consumer<S> committed) {
        this.capture = Objects.requireNonNull(capture, "capture");
        this.restore = Objects.requireNonNull(restore, "restore");
        this.committed = Objects.requireNonNull(committed, "committed");
    }

    /** Record against the exact context supplied by the Fabric Storage operation. */
    public boolean record(TransactionContext context) {
        if (context == null) return false;

        if (!rootSnapshotCaptured) {
            rootSnapshotCaptured = true;
            originalRootState = capture.get();
            // This callback runs after onFinalCommit and also on an outer abort.
            context.addOuterCloseCallback(result -> {
                rootSnapshotCaptured = false;
                originalRootState = null;
            });
        }
        updateSnapshots(context);
        return true;
    }

    /** Compatibility path for legacy handlers which only run inside an opened transaction. */
    public boolean recordCurrent() {
        return record(current());
    }

    public void record() {
        recordCurrent();
    }

    @Override protected S createSnapshot() {
        return capture.get();
    }

    @Override protected void readSnapshot(S value) {
        restore.accept(value);
    }

    @Override protected void onFinalCommit() {
        if (rootSnapshotCaptured) committed.accept(originalRootState);
    }

    /**
     * The legacy BCCE handlers do not accept an explicit TransactionContext.
     * This is the only point where Fabric's discouraged unsafe current-context
     * lookup is needed. Never cache or retain the returned context.
     */
    @SuppressWarnings("deprecation")
    public static TransactionContext current() {
        return Transaction.getCurrentUnsafe();
    }

    public static boolean active() {
        return Transaction.isOpen();
    }

    /** Notifications are absent on abort, including a nested abort. */
    public static boolean defer(Runnable notification) {
        Objects.requireNonNull(notification, "notification");
        TransactionContext context = current();
        if (context == null) return false;

        new SnapshotParticipant<Boolean>() {
            @Override protected Boolean createSnapshot() { return Boolean.TRUE; }
            @Override protected void readSnapshot(Boolean ignored) {}
            @Override protected void onFinalCommit() { notification.run(); }
        }.updateSnapshots(context);
        return true;
    }

    public static void notifyAfterCommit(Runnable notification) {
        if (!defer(notification)) notification.run();
    }
}
