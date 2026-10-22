package buildcraft.lib.platform.storage;

/** Internal FE-sized storage view. This is NOT an MJ/FE conversion and never changes energy units. */
public interface EnergyStorage {
    int receiveEnergy(int amount, boolean simulate);
    int extractEnergy(int amount, boolean simulate);
    int getEnergyStored();
    int getMaxEnergyStored();
    boolean canExtract();
    boolean canReceive();
}
