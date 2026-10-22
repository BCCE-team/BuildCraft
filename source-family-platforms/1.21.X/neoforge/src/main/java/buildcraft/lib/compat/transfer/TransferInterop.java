//? source if >=1.21.11
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.compat.transfer;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Standard NeoForge boundary for BCCE's OWN journaled storage. This is not a generic
 * legacy-to-transactional adapter: arbitrary foreign legacy handlers cannot be rolled back.
 * BCCE backing stores participate via TransferJournal, including shared sided views.
 * No transfer is postponed until commit; later operations observe speculative state.
 */
@SuppressWarnings("removal")
public final class TransferInterop {
    private TransferInterop() {}
    private static int bounded(long value) { return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value)); }

    public static ResourceHandler<ItemResource> exportItems(Supplier<IItemHandler> storage) {
        return exportItems(storage, () -> {});
    }

    public static ResourceHandler<ItemResource> exportItems(Supplier<IItemHandler> storage, Runnable dirty) {
        return new ResourceHandler<>() {
            public int size() { IItemHandler h = storage.get(); return h == null ? 0 : h.getSlots(); }
            private IItemHandler at(int index) {
                IItemHandler h = storage.get();
                Objects.checkIndex(index, h == null ? 0 : h.getSlots());
                return h;
            }
            public ItemResource getResource(int index) { return ItemResource.of(at(index).getStackInSlot(index)); }
            public long getAmountAsLong(int index) { return at(index).getStackInSlot(index).getCount(); }
            public long getCapacityAsLong(int index, ItemResource resource) {
                int limit = at(index).getSlotLimit(index);
                return resource.isEmpty() ? limit : Math.min(limit, resource.toStack().getMaxStackSize());
            }
            public boolean isValid(int index, ItemResource resource) {
                IItemHandler handler = at(index);
                return !resource.isEmpty() && handler.isItemValid(index, resource.toStack());
            }
            public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
                TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
                IItemHandler h = at(index);
                if (amount == 0) return 0;
                try (Transaction operation = Transaction.open(transaction)) {
                    int inserted = amount - h.insertItem(index, resource.toStack(amount), false).getCount();
                    if (inserted > 0) TransferJournal.notifyAfterCommit(dirty);
                    operation.commit();
                    return inserted;
                }
            }
            public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
                TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
                IItemHandler h = at(index);
                if (amount == 0 || !resource.equals(ItemResource.of(h.getStackInSlot(index)))) return 0;
                try (Transaction operation = Transaction.open(transaction)) {
                    int extracted = h.extractItem(index, amount, false).getCount();
                    if (extracted > 0) TransferJournal.notifyAfterCommit(dirty);
                    operation.commit();
                    return extracted;
                }
            }
        };
    }

    /** An insertion port, not an inventory: in-flight pipe items are not externally extractable. */
    public static ResourceHandler<ItemResource> exportItemSink(
        Supplier<buildcraft.lib.internal.inventory.IItemTransactor> storage, Runnable dirty
    ) {
        return exportItems(() -> {
            var transactor = storage.get();
            if (transactor == null) return null;
            return new IItemHandler() {
                public int getSlots() { return 1; }
                public ItemStack getStackInSlot(int index) { Objects.checkIndex(index, 1); return ItemStack.EMPTY; }
                public int getSlotLimit(int index) { Objects.checkIndex(index, 1); return Integer.MAX_VALUE; }
                public boolean isItemValid(int index, ItemStack stack) { Objects.checkIndex(index, 1); return !stack.isEmpty(); }
                public ItemStack insertItem(int index, ItemStack stack, boolean simulate) {
                    Objects.checkIndex(index, 1);
                    return transactor.insert(stack, false, simulate);
                }
                public ItemStack extractItem(int index, int amount, boolean simulate) {
                    Objects.checkIndex(index, 1);
                    return ItemStack.EMPTY;
                }
            };
        }, dirty);
    }

    public static ResourceHandler<FluidResource> exportFluids(Supplier<IFluidHandler> storage) {
        return exportFluids(storage, () -> {});
    }

    public static ResourceHandler<FluidResource> exportFluids(Supplier<IFluidHandler> storage, Runnable dirty) {
        return new ResourceHandler<>() {
            public int size() { IFluidHandler h = storage.get(); return h == null ? 0 : h.getTanks(); }
            private IFluidHandler at(int index) {
                IFluidHandler h = storage.get();
                Objects.checkIndex(index, h == null ? 0 : h.getTanks());
                return h;
            }
            public FluidResource getResource(int index) { return FluidResource.of(at(index).getFluidInTank(index)); }
            public long getAmountAsLong(int index) { return at(index).getFluidInTank(index).getAmount(); }
            public long getCapacityAsLong(int index, FluidResource resource) { return at(index).getTankCapacity(index); }
            public boolean isValid(int index, FluidResource resource) {
                IFluidHandler handler = at(index);
                return !resource.isEmpty() && handler.isFluidValid(index, resource.toStack(1));
            }
            public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
                TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
                IFluidHandler h = at(index);
                if (amount == 0) return 0;
                try (Transaction operation = Transaction.open(transaction)) {
                    int inserted;
                    if (h instanceof IndexedFluidHandler indexed) {
                        inserted = indexed.fillTank(index, resource.toStack(amount), FluidAction.EXECUTE);
                    } else if (h.getTanks() == 1) {
                        inserted = h.fill(resource.toStack(amount), FluidAction.EXECUTE);
                    } else {
                        throw new IllegalStateException("BCCE multi-tank handler has no indexed transfer implementation: " + h.getClass());
                    }
                    if (inserted > 0) TransferJournal.notifyAfterCommit(dirty);
                    operation.commit();
                    return inserted;
                }
            }
            public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
                TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
                IFluidHandler h = at(index);
                if (amount == 0 || !resource.equals(FluidResource.of(h.getFluidInTank(index)))) return 0;
                try (Transaction operation = Transaction.open(transaction)) {
                    FluidStack extracted;
                    if (h instanceof IndexedFluidHandler indexed) {
                        extracted = indexed.drainTank(index, resource.toStack(amount), FluidAction.EXECUTE);
                    } else if (h.getTanks() == 1) {
                        extracted = h.drain(resource.toStack(amount), FluidAction.EXECUTE);
                    } else {
                        throw new IllegalStateException("BCCE multi-tank handler has no indexed transfer implementation: " + h.getClass());
                    }
                    if (!extracted.isEmpty()) TransferJournal.notifyAfterCommit(dirty);
                    operation.commit();
                    return extracted.getAmount();
                }
            }
            // Legacy TankManager has a deliberate fill-only / drain-only priority order.
            public int insert(FluidResource resource, int amount, TransactionContext transaction) {
                TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
                IFluidHandler h = storage.get();
                if (h == null || amount == 0) return 0;
                try (Transaction operation = Transaction.open(transaction)) {
                    int inserted = h.fill(resource.toStack(amount), FluidAction.EXECUTE);
                    if (inserted > 0) TransferJournal.notifyAfterCommit(dirty);
                    operation.commit();
                    return inserted;
                }
            }
            public int extract(FluidResource resource, int amount, TransactionContext transaction) {
                TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
                IFluidHandler h = storage.get();
                if (h == null || amount == 0) return 0;
                try (Transaction operation = Transaction.open(transaction)) {
                    int extracted = h.drain(resource.toStack(amount), FluidAction.EXECUTE).getAmount();
                    if (extracted > 0) TransferJournal.notifyAfterCommit(dirty);
                    operation.commit();
                    return extracted;
                }
            }
        };
    }

    public static EnergyHandler exportEnergy(Supplier<IEnergyStorage> storage) {
        return exportEnergy(storage, () -> {});
    }

    public static EnergyHandler exportEnergy(Supplier<IEnergyStorage> storage, Runnable dirty) {
        return new EnergyHandler() {
            public long getAmountAsLong() { IEnergyStorage h = storage.get(); return h == null ? 0 : h.getEnergyStored(); }
            public long getCapacityAsLong() { IEnergyStorage h = storage.get(); return h == null ? 0 : h.getMaxEnergyStored(); }
            public int insert(int amount, TransactionContext transaction) {
                if (amount < 0) throw new IllegalArgumentException("Negative energy amount");
                IEnergyStorage h = storage.get();
                if (h == null || amount == 0 || !h.canReceive()) return 0;
                try (Transaction operation = Transaction.open(transaction)) {
                    int inserted = h.receiveEnergy(amount, false);
                    if (inserted > 0) TransferJournal.notifyAfterCommit(dirty);
                    operation.commit();
                    return inserted;
                }
            }
            public int extract(int amount, TransactionContext transaction) {
                if (amount < 0) throw new IllegalArgumentException("Negative energy amount");
                IEnergyStorage h = storage.get();
                if (h == null || amount == 0 || !h.canExtract()) return 0;
                try (Transaction operation = Transaction.open(transaction)) {
                    int extracted = h.extractEnergy(amount, false);
                    if (extracted > 0) TransferJournal.notifyAfterCommit(dirty);
                    operation.commit();
                    return extracted;
                }
            }
        };
    }

    /** Unlike NeoForge's temporary IItemHandler.of(), this view also works INSIDE an existing transaction. */
    public static IItemHandler importItems(ResourceHandler<ItemResource> source) {
        return new IItemHandler() {
            public int getSlots() { return source.size(); }
            public ItemStack getStackInSlot(int slot) { return source.getResource(slot).toStack(bounded(source.getAmountAsLong(slot))); }
            public int getSlotLimit(int slot) { return bounded(source.getCapacityAsLong(slot, ItemResource.EMPTY)); }
            public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty() && source.isValid(slot, ItemResource.of(stack)); }
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (stack.isEmpty()) return ItemStack.EMPTY;
                try (Transaction operation = Transaction.open(TransferJournal.current())) {
                    int inserted = source.insert(slot, ItemResource.of(stack), stack.getCount(), operation);
                    if (!simulate) operation.commit();
                    return stack.copyWithCount(stack.getCount() - inserted);
                }
            }
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemResource resource = source.getResource(slot);
                if (resource.isEmpty() || amount <= 0) return ItemStack.EMPTY;
                // Legacy extraction returns at most one stack, even when native storage is very large.
                int limit = Math.min(amount, resource.toStack().getMaxStackSize());
                try (Transaction operation = Transaction.open(TransferJournal.current())) {
                    int extracted = source.extract(slot, resource, limit, operation);
                    if (!simulate) operation.commit();
                    return resource.toStack(extracted);
                }
            }
        };
    }

    public static IFluidHandler importFluids(ResourceHandler<FluidResource> source) {
        return new IFluidHandler() {
            public int getTanks() { return source.size(); }
            public FluidStack getFluidInTank(int tank) { return source.getResource(tank).toStack(bounded(source.getAmountAsLong(tank))); }
            public int getTankCapacity(int tank) { return bounded(source.getCapacityAsLong(tank, FluidResource.EMPTY)); }
            public boolean isFluidValid(int tank, FluidStack stack) { return !stack.isEmpty() && source.isValid(tank, FluidResource.of(stack)); }
            public int fill(FluidStack resource, FluidAction action) {
                if (resource.isEmpty()) return 0;
                try (Transaction operation = Transaction.open(TransferJournal.current())) {
                    int inserted = source.insert(FluidResource.of(resource), resource.getAmount(), operation);
                    if (action.execute()) operation.commit();
                    return inserted;
                }
            }
            public FluidStack drain(FluidStack resource, FluidAction action) {
                if (resource.isEmpty()) return FluidStack.EMPTY;
                try (Transaction operation = Transaction.open(TransferJournal.current())) {
                    int extracted = source.extract(FluidResource.of(resource), resource.getAmount(), operation);
                    if (action.execute()) operation.commit();
                    return resource.copyWithAmount(extracted);
                }
            }
            public FluidStack drain(int amount, FluidAction action) {
                if (amount <= 0) return FluidStack.EMPTY;
                for (int i = 0; i < source.size(); i++) {
                    FluidResource resource = source.getResource(i);
                    if (resource.isEmpty()) continue;
                    FluidStack result = drain(resource.toStack(amount), action);
                    if (!result.isEmpty()) return result;
                }
                return FluidStack.EMPTY;
            }
        };
    }

    public static IEnergyStorage importEnergy(EnergyHandler source) {
        return new IEnergyStorage() {
            public int getEnergyStored() { return bounded(source.getAmountAsLong()); }
            public int getMaxEnergyStored() { return bounded(source.getCapacityAsLong()); }
            public int receiveEnergy(int amount, boolean simulate) {
                if (amount <= 0) return 0;
                try (Transaction operation = Transaction.open(TransferJournal.current())) {
                    int inserted = source.insert(amount, operation);
                    if (!simulate) operation.commit();
                    return inserted;
                }
            }
            public int extractEnergy(int amount, boolean simulate) {
                if (amount <= 0) return 0;
                try (Transaction operation = Transaction.open(TransferJournal.current())) {
                    int extracted = source.extract(amount, operation);
                    if (!simulate) operation.commit();
                    return extracted;
                }
            }
            public boolean canReceive() { return receiveEnergy(Integer.MAX_VALUE, true) > 0; }
            public boolean canExtract() { return extractEnergy(Integer.MAX_VALUE, true) > 0; }
        };
    }
}
