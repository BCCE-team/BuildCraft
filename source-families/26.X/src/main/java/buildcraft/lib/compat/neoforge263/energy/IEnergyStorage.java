//? source if >=26.3
package buildcraft.lib.compat.neoforge263.energy;

/** Internal classic FE-sized storage used behind 26.3's EnergyHandler boundary. */
public interface IEnergyStorage {
    int receiveEnergy(int maxReceive, boolean simulate);
    int extractEnergy(int maxExtract, boolean simulate);
    int getEnergyStored();
    int getMaxEnergyStored();
    boolean canExtract();
    boolean canReceive();
}
