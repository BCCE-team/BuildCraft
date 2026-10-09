//? source if >=26.3
package buildcraft.transport.compat;

import buildcraft.lib.compat.transfer.TransferJournal;
import buildcraft.lib.platform.storage.EnergyStorage;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

public final class NativeEnergyStorage263 implements EnergyStorage {
    private final EnergyHandler handler;
    public NativeEnergyStorage263(EnergyHandler handler) { this.handler = java.util.Objects.requireNonNull(handler); }
    public int receiveEnergy(int amount, boolean simulate) {
        if (amount <= 0) return 0;
        try (Transaction tx = Transaction.open(TransferJournal.current())) {
            int added = handler.insert(amount, tx);
            if (!simulate && added > 0) tx.commit();
            return Math.max(0, Math.min(amount, added));
        }
    }
    public int extractEnergy(int amount, boolean simulate) {
        if (amount <= 0) return 0;
        try (Transaction tx = Transaction.open(TransferJournal.current())) {
            int taken = handler.extract(amount, tx);
            if (!simulate && taken > 0) tx.commit();
            return Math.max(0, Math.min(amount, taken));
        }
    }
    public int getEnergyStored() { return handler.getAmountAsInt(); }
    public int getMaxEnergyStored() { return handler.getCapacityAsInt(); }
    public boolean canExtract() { return true; }
    public boolean canReceive() { return true; }
}
