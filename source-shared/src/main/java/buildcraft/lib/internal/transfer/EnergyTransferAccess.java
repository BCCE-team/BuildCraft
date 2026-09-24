package buildcraft.lib.internal.transfer;

/** Internal loader-neutral external-energy endpoint. Amounts remain in the native external-energy unit. */
public interface EnergyTransferAccess {
    long insert(long offered, OperationScope scope);
    long extract(long requested, OperationScope scope);
    long stored();
    long capacity();
    boolean canInsert();
    boolean canExtract();
}
