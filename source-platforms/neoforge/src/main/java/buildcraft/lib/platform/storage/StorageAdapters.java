package buildcraft.lib.platform.storage;

import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;
import buildcraft.lib.internal.core.IFluidHandlerAdv;
import buildcraft.lib.internal.inventory.IItemHandlerFiltered;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** Lossless operation adapters. Unwrapping round-trips keeps native identity and transaction behavior.
 * These views do not cache a capability: the lookup owner retains its original invalidation lifecycle. */
public final class StorageAdapters {
    private StorageAdapters() {}
    public static ItemStorage fromNativeItems(IItemHandler nativeStorage) {
        if (nativeStorage == null) return null;
        if (nativeStorage instanceof NativeItems wrapped) return wrapped.storage;
        if (nativeStorage instanceof IItemHandlerModifiable mutable && nativeStorage instanceof IItemHandlerFiltered filtered) {
            return new MutableFilteredItems(mutable, filtered);
        }
        if (nativeStorage instanceof IItemHandlerModifiable mutable) return new MutableItems(mutable);
        if (nativeStorage instanceof IItemHandlerFiltered filtered) return new FilteredItems(filtered);
        return new Items(nativeStorage);
    }
    public static IItemHandler toNativeItems(ItemStorage storage) {
        if (storage == null) return null;
        if (storage instanceof Items wrapped) return wrapped.storage;
        return storage instanceof MutableItemStorage mutable ? new NativeMutableItems(mutable) : new NativeItems(storage);
    }
    public static EnergyStorage fromNativeEnergy(IEnergyStorage nativeStorage) {
        if (nativeStorage == null) return null;
        if (nativeStorage instanceof NativeEnergy wrapped) return wrapped.storage;
        return new Energy(nativeStorage);
    }
    public static IEnergyStorage toNativeEnergy(EnergyStorage storage) {
        if (storage == null) return null;
        if (storage instanceof Energy wrapped) return wrapped.storage;
        return new NativeEnergy(storage);
    }
    public static FluidStorage<FluidStack> fromNativeFluids(IFluidHandler nativeStorage) {
        if (nativeStorage == null) return null;
        if (nativeStorage instanceof NativeFluids wrapped) return wrapped.storage;
        return nativeStorage instanceof IFluidHandlerAdv advanced ? new FilteredFluids(advanced) : new Fluids(nativeStorage);
    }
    public static IFluidHandler toNativeFluids(FluidStorage<FluidStack> storage) {
        if (storage == null) return null;
        if (storage instanceof Fluids wrapped) return wrapped.storage;
        if (storage instanceof FilteredFluidStorage<FluidStack> advanced) return new NativeFilteredFluids(advanced);
        return new NativeFluids(storage);
    }
    private static FluidAction action(boolean simulate) { return simulate ? FluidAction.SIMULATE : FluidAction.EXECUTE; }

    private static class Items implements ItemStorage {
        final IItemHandler storage;
        Items(IItemHandler storage) { this.storage = Objects.requireNonNull(storage); }
        public int getSlots() { return storage.getSlots(); }
        public ItemStack getStackInSlot(int slot) { return storage.getStackInSlot(slot); }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return storage.insertItem(slot, stack, simulate); }
        public ItemStack extractItem(int slot, int amount, boolean simulate) { return storage.extractItem(slot, amount, simulate); }
        public int getSlotLimit(int slot) { return storage.getSlotLimit(slot); }
        public boolean isItemValid(int slot, ItemStack stack) { return storage.isItemValid(slot, stack); }
    }
    private static class MutableItems extends Items implements MutableItemStorage {
        private final IItemHandlerModifiable mutable;
        MutableItems(IItemHandlerModifiable storage) { super(storage); mutable = storage; }
        public void setStackInSlot(int slot, ItemStack stack) { mutable.setStackInSlot(slot, stack); }
    }
    private static class FilteredItems extends Items implements FilteredItemStorage {
        private final IItemHandlerFiltered filtered;
        FilteredItems(IItemHandlerFiltered storage) { super(storage); filtered = storage; }
        public ItemStack getFilter(int slot) { return filtered.getFilter(slot); }
    }
    private static final class MutableFilteredItems extends MutableItems implements FilteredItemStorage {
        private final IItemHandlerFiltered filtered;
        MutableFilteredItems(IItemHandlerModifiable storage, IItemHandlerFiltered filtered) {
            super(storage);
            this.filtered = filtered;
        }
        public ItemStack getFilter(int slot) { return filtered.getFilter(slot); }
    }
    private static class NativeItems implements IItemHandler {
        final ItemStorage storage;
        NativeItems(ItemStorage storage) { this.storage = Objects.requireNonNull(storage); }
        public int getSlots() { return storage.getSlots(); }
        public ItemStack getStackInSlot(int slot) { return storage.getStackInSlot(slot); }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return storage.insertItem(slot, stack, simulate); }
        public ItemStack extractItem(int slot, int amount, boolean simulate) { return storage.extractItem(slot, amount, simulate); }
        public int getSlotLimit(int slot) { return storage.getSlotLimit(slot); }
        public boolean isItemValid(int slot, ItemStack stack) { return storage.isItemValid(slot, stack); }
    }
    private static final class NativeMutableItems extends NativeItems implements IItemHandlerModifiable {
        private final MutableItemStorage mutable;
        NativeMutableItems(MutableItemStorage storage) { super(storage); mutable = storage; }
        public void setStackInSlot(int slot, ItemStack stack) { mutable.setStackInSlot(slot, stack); }
    }
    private static final class Energy implements EnergyStorage {
        final IEnergyStorage storage;
        Energy(IEnergyStorage storage) { this.storage = Objects.requireNonNull(storage); }
        public int receiveEnergy(int amount, boolean simulate) { return storage.receiveEnergy(amount, simulate); }
        public int extractEnergy(int amount, boolean simulate) { return storage.extractEnergy(amount, simulate); }
        public int getEnergyStored() { return storage.getEnergyStored(); }
        public int getMaxEnergyStored() { return storage.getMaxEnergyStored(); }
        public boolean canExtract() { return storage.canExtract(); }
        public boolean canReceive() { return storage.canReceive(); }
    }
    private static final class NativeEnergy implements IEnergyStorage {
        final EnergyStorage storage;
        NativeEnergy(EnergyStorage storage) { this.storage = Objects.requireNonNull(storage); }
        public int receiveEnergy(int amount, boolean simulate) { return storage.receiveEnergy(amount, simulate); }
        public int extractEnergy(int amount, boolean simulate) { return storage.extractEnergy(amount, simulate); }
        public int getEnergyStored() { return storage.getEnergyStored(); }
        public int getMaxEnergyStored() { return storage.getMaxEnergyStored(); }
        public boolean canExtract() { return storage.canExtract(); }
        public boolean canReceive() { return storage.canReceive(); }
    }
    private static class Fluids implements FluidStorage<FluidStack> {
        final IFluidHandler storage;
        Fluids(IFluidHandler storage) { this.storage = Objects.requireNonNull(storage); }
        public int getTanks() { return storage.getTanks(); }
        public FluidStack getFluidInTank(int tank) { return storage.getFluidInTank(tank); }
        public int getTankCapacity(int tank) { return storage.getTankCapacity(tank); }
        public boolean isFluidValid(int tank, FluidStack fluid) { return storage.isFluidValid(tank, fluid); }
        public int fill(FluidStack fluid, boolean simulate) { return storage.fill(fluid, action(simulate)); }
        public FluidStack drain(FluidStack fluid, boolean simulate) { return storage.drain(fluid, action(simulate)); }
        public FluidStack drain(int amount, boolean simulate) { return storage.drain(amount, action(simulate)); }
    }
    private static final class FilteredFluids extends Fluids implements FilteredFluidStorage<FluidStack> {
        final IFluidHandlerAdv advanced;
        FilteredFluids(IFluidHandlerAdv storage) { super(storage); advanced = storage; }
        public FluidStack drain(Predicate<FluidStack> filter, int amount, boolean simulate) { return advanced.drain(filter::test, amount, action(simulate)); }
    }
    private static class NativeFluids implements IFluidHandler {
        final FluidStorage<FluidStack> storage;
        NativeFluids(FluidStorage<FluidStack> storage) { this.storage = Objects.requireNonNull(storage); }
        public int getTanks() { return storage.getTanks(); }
        public FluidStack getFluidInTank(int tank) { return storage.getFluidInTank(tank); }
        public int getTankCapacity(int tank) { return storage.getTankCapacity(tank); }
        public boolean isFluidValid(int tank, FluidStack fluid) { return storage.isFluidValid(tank, fluid); }
        public int fill(FluidStack fluid, FluidAction action) { return storage.fill(fluid, action.simulate()); }
        public FluidStack drain(FluidStack fluid, FluidAction action) { return storage.drain(fluid, action.simulate()); }
        public FluidStack drain(int amount, FluidAction action) { return storage.drain(amount, action.simulate()); }
    }
    private static final class NativeFilteredFluids extends NativeFluids implements IFluidHandlerAdv {
        final FilteredFluidStorage<FluidStack> advanced;
        NativeFilteredFluids(FilteredFluidStorage<FluidStack> storage) { super(storage); advanced = storage; }
        public FluidStack drain(buildcraft.lib.internal.core.IFluidFilter filter, int amount, FluidAction action) { return advanced.drain(filter::matches, amount, action.simulate()); }
    }
}
