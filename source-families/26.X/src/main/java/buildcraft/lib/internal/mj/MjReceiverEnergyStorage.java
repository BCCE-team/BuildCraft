package buildcraft.lib.internal.mj;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** FE view over an MJ receiver. Used by powerMode autoconversion. */
public final class MjReceiverEnergyStorage implements IEnergyStorage {
    private final IMjReceiver receiver;
    private final IMjReadable readable;

    public MjReceiverEnergyStorage(IMjReceiver receiver) {
        this.receiver = receiver;
        this.readable = receiver instanceof IMjReadable r ? r : null;
    }

    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (maxReceive <= 0 || !canReceive()) return 0;
        long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
        if (ratio <= 0) return 0;

        long offeredMj = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().feToMicroMj(maxReceive);
        long simulatedExcess = receiver.receivePower(offeredMj, FluidAction.SIMULATE);
        long simulatedAcceptedMj = Math.max(0, offeredMj - clampExcess(simulatedExcess, offeredMj));
        int transferableFe = (int) Math.min(maxReceive, simulatedAcceptedMj / ratio);
        if (simulate || transferableFe <= 0) return transferableFe;

        // Execute only an exact FE-sized amount. This keeps MJ/FE conversion conservative even when
        // an MJ receiver's free space is not aligned to the configured conversion ratio.
        long executableMj = (long) transferableFe * ratio;
        long executeExcess = receiver.receivePower(executableMj, FluidAction.EXECUTE);
        long executedAcceptedMj = Math.max(0, executableMj - clampExcess(executeExcess, executableMj));
        return (int) Math.min(transferableFe, executedAcceptedMj / ratio);
    }

    private static long clampExcess(long excess, long offered) {
        return Math.max(0, Math.min(offered, excess));
    }

    public int extractEnergy(int maxExtract, boolean simulate) { return 0; }
    public boolean canExtract() { return false; }
    public boolean canReceive() { return BuildCraftApi.service(BuildCraftServices.ENERGY).automaticFeConversionEnabled() && receiver.canReceive(); }
    public int getEnergyStored() { return readable == null ? 0 : (int)Math.min(Integer.MAX_VALUE, BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjToWholeFe(readable.getStored())); }
    public int getMaxEnergyStored() {
        if (readable != null) {
            return (int) Math.min(Integer.MAX_VALUE, BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjToWholeFe(readable.getCapacity()));
        }
        // A write-only MJ receiver has no storage-capacity metadata. Do not substitute current demand here:
        // getPowerRequested() may perform server-only simulation (wooden pipes do), and FE metadata is queried client-side.
        return Integer.MAX_VALUE;
    }
}
