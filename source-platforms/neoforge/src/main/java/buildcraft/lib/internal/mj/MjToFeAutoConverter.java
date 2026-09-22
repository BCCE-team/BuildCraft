package buildcraft.lib.internal.mj;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.energy.EnergyConversion;
import buildcraft.lib.platform.storage.EnergyStorage;
import buildcraft.lib.platform.storage.StorageAdapters;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Presents an FE receiver as an MJ receiver when automatic conversion is enabled. */
public final class MjToFeAutoConverter implements IMjReceiver, IMjReadable {
    private final EnergyStorage fe;

    private MjToFeAutoConverter(EnergyStorage fe) {
        this.fe = fe;
    }

    public static IMjReceiver createReceiver(EnergyStorage fe) {
        if (fe == null || !fe.canReceive() || !BuildCraftApi.service(BuildCraftServices.ENERGY).automaticFeConversionEnabled()) return null;
        return new MjToFeAutoConverter(fe);
    }

    /** Native compatibility entry point; gameplay should pass the internal storage view. */
    public static IMjReceiver createReceiver(IEnergyStorage fe) {
        return createReceiver(StorageAdapters.fromNativeEnergy(fe));
    }

    @Override
    public boolean canConnect(IMjConnector other) { return true; }

    @Override
    public boolean canReceive() { return fe.canReceive() && BuildCraftApi.service(BuildCraftServices.ENERGY).automaticFeConversionEnabled(); }

    @Override
    public long getPowerRequested() {
        if (!canReceive()) return 0;
        // Simulation works for bufferless FE machines too; maxStored - stored does not.
        int simulated = Math.max(0, fe.receiveEnergy(Integer.MAX_VALUE, true));
        long requestedFe = simulated > 0
            ? simulated
            : Math.max(0L, (long) fe.getMaxEnergyStored() - fe.getEnergyStored());
        return BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().feToMicroMj(requestedFe);
    }

    @Override
    public long receivePower(long microJoules, FluidAction action) {
        if (!canReceive() || microJoules <= 0) return microJoules;
        long ratio = BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().microMjPerFe();
        long convertible = microJoules / ratio;
        if (convertible <= 0) return microJoules;
        int offeredFe = (int) Math.min(Integer.MAX_VALUE, convertible);
        // Capabilities belong to other mods. Keep this boundary conservative even if one returns
        // an invalid result (negative or greater than the amount it was offered).
        int acceptedFe = clampAccepted(fe.receiveEnergy(offeredFe, action == FluidAction.SIMULATE), offeredFe);
        return microJoules - (long) acceptedFe * ratio;
    }

    private static int clampAccepted(int accepted, int offered) {
        return Math.max(0, Math.min(offered, accepted));
    }

    @Override
    public long getStored() { return BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().feToMicroMj(Math.max(0, fe.getEnergyStored())); }

    @Override
    public long getCapacity() { return BuildCraftApi.service(BuildCraftServices.ENERGY).conversion().feToMicroMj(Math.max(0, fe.getMaxEnergyStored())); }
}
