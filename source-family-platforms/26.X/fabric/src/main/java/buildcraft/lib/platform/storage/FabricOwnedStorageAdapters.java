/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.lib.platform.storage;

import buildcraft.lib.compat.neoforge263.energy.IEnergyStorage;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler;
import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler.FluidAction;
import buildcraft.lib.compat.neoforge263.items.IItemHandler;
import buildcraft.lib.compat.transfer.TransferJournal;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.internal.inventory.IItemHandlerFiltered;
import buildcraft.api.v2.item.ItemMatcher;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric-native views of BCCE-owned legacy storages. No external NeoForge types.
 * Every write executes inside a nested Fabric transaction so the existing
 * TransferJournal snapshot belongs to the caller's transaction, not to an
 * accidentally independent root. No fractional-millibucket transfer is made.
 *
 * This adapter MUST NOT wrap an arbitrary unjournaled third-party handler.
 */
public final class FabricOwnedStorageAdapters {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;
    private FabricOwnedStorageAdapters() {}

    public static SlottedStorage<ItemVariant> items(IItemHandler handler, Runnable changed) {
        return new OwnedItems(Objects.requireNonNull(handler), Objects.requireNonNull(changed));
    }
    public static SlottedStorage<FluidVariant> fluids(IFluidHandler handler, Runnable changed) {
        return new OwnedFluids(Objects.requireNonNull(handler), Objects.requireNonNull(changed));
    }
    public static team.reborn.energy.api.EnergyStorage energy(IEnergyStorage handler, Runnable changed) {
        return new OwnedEnergy(Objects.requireNonNull(handler), Objects.requireNonNull(changed));
    }

    private static int bounded(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }
    private static long wholeMb(long droplets) {
        return Math.max(0L, droplets / DROPLETS_PER_MB);
    }

    private static final class OwnedItems implements SlottedStorage<ItemVariant>, FabricSlotFilterProvider {
        final IItemHandler handler;
        final Runnable changed;
        OwnedItems(IItemHandler handler, Runnable changed) { this.handler = handler; this.changed = changed; }
        public int getSlotCount() { return handler.getSlots(); }
        public ItemMatcher filter(int slot) {
            if (slot < 0 || slot >= handler.getSlots()) return ItemMatcher.none();
            if (!(handler instanceof IItemHandlerFiltered filtered)) return ItemMatcher.any();
            ItemStack example = filtered.getFilter(slot);
            if (example == null || example.isEmpty()) return ItemMatcher.any();
            ItemStack normalized = example.copyWithCount(1);
            return candidate -> candidate != null && !candidate.isEmpty()
                && ItemStack.matches(normalized, candidate.copyWithCount(1));
        }
        public SingleSlotStorage<ItemVariant> getSlot(int slot) {
            Objects.checkIndex(slot, handler.getSlots());
            return new ItemSlot(this, slot);
        }
        public Iterator<StorageView<ItemVariant>> iterator() {
            return IntStream.range(0, getSlotCount())
                .mapToObj(i -> (StorageView<ItemVariant>) getSlot(i)).iterator();
        }
        public long insert(ItemVariant variant, long amount, TransactionContext transaction) {
            long remaining = Math.min(amount, Integer.MAX_VALUE);
            long inserted = 0;
            for (int i=0; i<getSlotCount() && remaining>0; i++) {
                long moved = getSlot(i).insert(variant, remaining, transaction);
                inserted += moved;
                remaining -= moved;
            }
            return inserted;
        }
        public long extract(ItemVariant variant, long amount, TransactionContext transaction) {
            long remaining = Math.min(amount, Integer.MAX_VALUE);
            long extracted = 0;
            for (int i=0; i<getSlotCount() && remaining>0; i++) {
                long moved = getSlot(i).extract(variant, remaining, transaction);
                extracted += moved;
                remaining -= moved;
            }
            return extracted;
        }
        public boolean supportsInsertion() { return true; }
        public boolean supportsExtraction() { return true; }
    }

    private static final class ItemSlot implements SingleSlotStorage<ItemVariant> {
        final OwnedItems parent;
        final int index;
        ItemSlot(OwnedItems parent, int index) { this.parent = parent; this.index = index; }
        public ItemVariant getResource() {
            return ItemVariant.of(parent.handler.getStackInSlot(index));
        }
        public long getAmount() { return parent.handler.getStackInSlot(index).getCount(); }
        public long getCapacity() { return Math.max(0, parent.handler.getSlotLimit(index)); }
        public boolean isResourceBlank() { return parent.handler.getStackInSlot(index).isEmpty(); }
        public boolean supportsInsertion() { return true; }
        public boolean supportsExtraction() { return true; }
        public long insert(ItemVariant variant, long requested, TransactionContext context) {
            if (variant == null || variant.isBlank() || requested <= 0) return 0L;
            ItemStack example = variant.toStack();
            if (!parent.handler.isItemValid(index, example)) return 0L;
            int count = Math.min(bounded(requested), example.getMaxStackSize());
            if (count <= 0) return 0L;
            try (Transaction nested = Transaction.openNested(context)) {
                int remaining = parent.handler.insertItem(index, variant.toStack(count), false).getCount();
                int moved = Math.max(0, Math.min(count, count - remaining));
                if (moved > 0) TransferJournal.notifyAfterCommit(parent.changed);
                nested.commit();
                return moved;
            }
        }
        public long extract(ItemVariant variant, long requested, TransactionContext context) {
            if (variant == null || variant.isBlank() || requested <= 0 || !variant.equals(getResource())) return 0L;
            try (Transaction nested = Transaction.openNested(context)) {
                ItemStack removed = parent.handler.extractItem(index, bounded(requested), false);
                if (!removed.isEmpty() && !ItemVariant.of(removed).equals(variant)) {
                    throw new IllegalStateException("Item handler extracted a different component-bearing item");
                }
                int moved = removed.getCount();
                if (moved > 0) TransferJournal.notifyAfterCommit(parent.changed);
                nested.commit();
                return moved;
            }
        }
    }

    private static final class OwnedFluids implements SlottedStorage<FluidVariant> {
        final IFluidHandler handler;
        final Runnable changed;
        OwnedFluids(IFluidHandler handler, Runnable changed) { this.handler = handler; this.changed = changed; }
        public int getSlotCount() { return handler.getTanks(); }
        public SingleSlotStorage<FluidVariant> getSlot(int slot) {
            Objects.checkIndex(slot, handler.getTanks());
            return new FluidSlot(this, slot);
        }
        public Iterator<StorageView<FluidVariant>> iterator() {
            return IntStream.range(0, getSlotCount())
                .mapToObj(i -> (StorageView<FluidVariant>) getSlot(i)).iterator();
        }
        public long insert(FluidVariant variant, long droplets, TransactionContext context) {
            if (variant == null || variant.isBlank() || droplets < DROPLETS_PER_MB) return 0L;
            long remaining = Math.min(wholeMb(droplets), Integer.MAX_VALUE);
            try (Transaction nested = Transaction.openNested(context)) {
                int moved = parentFill(variant, bounded(remaining));
                if (moved > 0) TransferJournal.notifyAfterCommit(changed);
                nested.commit();
                return (long) moved * DROPLETS_PER_MB;
            }
        }
        private int parentFill(FluidVariant variant, int amount) {
            return handler.fill(toStack(variant, amount), FluidAction.EXECUTE);
        }
        public long extract(FluidVariant variant, long droplets, TransactionContext context) {
            if (variant == null || variant.isBlank() || droplets < DROPLETS_PER_MB) return 0L;
            try (Transaction nested = Transaction.openNested(context)) {
                FluidStack removed = handler.drain(toStack(variant, bounded(wholeMb(droplets))), FluidAction.EXECUTE);
                if (!removed.isEmpty() && !variant.equals(toVariant(removed)))
                    throw new IllegalStateException("Fluid handler returned a different fluid identity");
                int moved = removed.getAmount();
                if (moved > 0) TransferJournal.notifyAfterCommit(changed);
                nested.commit();
                return (long) moved * DROPLETS_PER_MB;
            }
        }
        public boolean supportsInsertion() { return true; }
        public boolean supportsExtraction() { return true; }
    }

    private static FluidStack toStack(FluidVariant variant, int amount) {
        return variant == null || variant.isBlank() || amount <= 0
            ? FluidStack.EMPTY : new FluidStack(variant.getFluid(), amount, variant.getComponentsPatch());
    }
    private static FluidVariant toVariant(FluidStack stack) {
        return stack == null || stack.isEmpty() ? FluidVariant.blank()
            : FluidVariant.of(stack.getFluid(), stack.getComponentsPatch());
    }

    private static final class FluidSlot implements SingleSlotStorage<FluidVariant> {
        final OwnedFluids parent;
        final int index;
        FluidSlot(OwnedFluids parent, int index) { this.parent = parent; this.index = index; }
        public FluidVariant getResource() { return toVariant(parent.handler.getFluidInTank(index)); }
        public long getAmount() { return (long) parent.handler.getFluidInTank(index).getAmount() * DROPLETS_PER_MB; }
        public long getCapacity() { return (long) parent.handler.getTankCapacity(index) * DROPLETS_PER_MB; }
        public boolean isResourceBlank() { return parent.handler.getFluidInTank(index).isEmpty(); }
        public boolean supportsInsertion() { return true; }
        public boolean supportsExtraction() { return true; }
        public long insert(FluidVariant variant, long droplets, TransactionContext context) {
            if (variant == null || variant.isBlank() || droplets < DROPLETS_PER_MB) return 0L;
            int amount = bounded(wholeMb(droplets));
            FluidStack offered = toStack(variant, amount);
            if (!parent.handler.isFluidValid(index, offered)) return 0L;
            try (Transaction nested = Transaction.openNested(context)) {
                int moved;
                if (parent.handler instanceof buildcraft.lib.compat.transfer.IndexedFluidHandler indexed) {
                    moved = indexed.fillTank(index, offered, FluidAction.EXECUTE);
                } else if (parent.handler.getTanks() == 1) {
                    moved = parent.handler.fill(offered, FluidAction.EXECUTE);
                } else return 0L; // Never fill an arbitrary tank from a different slot.
                if (moved > 0) TransferJournal.notifyAfterCommit(parent.changed);
                nested.commit();
                return (long) moved * DROPLETS_PER_MB;
            }
        }
        public long extract(FluidVariant variant, long droplets, TransactionContext context) {
            if (variant == null || variant.isBlank() || droplets < DROPLETS_PER_MB || !variant.equals(getResource())) return 0L;
            FluidStack offered = toStack(variant, bounded(wholeMb(droplets)));
            try (Transaction nested = Transaction.openNested(context)) {
                FluidStack removed;
                if (parent.handler instanceof buildcraft.lib.compat.transfer.IndexedFluidHandler indexed) {
                    removed = indexed.drainTank(index, offered, FluidAction.EXECUTE);
                } else if (parent.handler.getTanks() == 1) {
                    removed = parent.handler.drain(offered, FluidAction.EXECUTE);
                } else return 0L;
                if (!removed.isEmpty() && !variant.equals(toVariant(removed)))
                    throw new IllegalStateException("Tank extracted a different component-bearing fluid");
                int moved = removed.getAmount();
                if (moved > 0) TransferJournal.notifyAfterCommit(parent.changed);
                nested.commit();
                return (long) moved * DROPLETS_PER_MB;
            }
        }
    }

    /**
     * A classic FE receiver is not a Fabric SnapshotParticipant. Mutating it during
     * insert() would leak energy when a parent transaction aborts. Instead this
     * single handler view reserves a signed delta, maintains Fabric savepoints,
     * and applies the entire delta only after the outer transaction commits.
     *
     * getAmount() includes the tentative delta, as Fabric storage contracts
     * require. The underlying handler is only used for side-effect-free probes
     * until onFinalCommit().
     */
    private static final class OwnedEnergy extends SnapshotParticipant<Long>
        implements team.reborn.energy.api.EnergyStorage {
        private final IEnergyStorage handler;
        private final Runnable changed;
        private long pendingDelta;

        OwnedEnergy(IEnergyStorage handler, Runnable changed) {
            this.handler = handler;
            this.changed = changed;
        }

        @Override protected Long createSnapshot() { return pendingDelta; }
        @Override protected void readSnapshot(Long previous) { pendingDelta = previous; }

        @Override protected void onFinalCommit() {
            long toApply = pendingDelta;
            pendingDelta = 0L;
            if (toApply == 0L) return;
            // The owner's FE capability uses a bounded integer unit. A single
            // pending delta fits the visible capacity; reject partial commits.
            int promised = bounded(Math.abs(toApply));
            int actual = toApply > 0
                ? handler.receiveEnergy(promised, false)
                : handler.extractEnergy(promised, false);
            if (actual != promised) {
                throw new IllegalStateException(
                    "FE endpoint violated its simulation/commit agreement: expected="
                        + promised + " actual=" + actual);
            }
            changed.run();
        }

        @Override public boolean supportsInsertion() { return handler.canReceive(); }
        @Override public boolean supportsExtraction() { return handler.canExtract(); }
        @Override public long getAmount() {
            return Math.max(0L, Math.min(getCapacity(), (long) handler.getEnergyStored() + pendingDelta));
        }
        @Override public long getCapacity() { return Math.max(0L, handler.getMaxEnergyStored()); }

        @Override public long insert(long amount, TransactionContext transaction) {
            if (amount <= 0 || !supportsInsertion()) return 0;
            long simulatedFree = handler.receiveEnergy(Integer.MAX_VALUE, true);
            // A negative pending delta (extraction) increases free capacity.
            long available = Math.max(0L, simulatedFree - pendingDelta);
            long accepted = Math.min(amount, Math.min(available,
                Math.max(0L, getCapacity() - getAmount())));
            if (accepted <= 0L) return 0L;
            updateSnapshots(transaction);
            pendingDelta += accepted;
            return accepted;
        }

        @Override public long extract(long amount, TransactionContext transaction) {
            if (amount <= 0 || !supportsExtraction()) return 0;
            long simulatedStored = handler.extractEnergy(Integer.MAX_VALUE, true);
            long available = Math.max(0L, simulatedStored + pendingDelta);
            long extracted = Math.min(amount, Math.min(available, getAmount()));
            if (extracted <= 0L) return 0L;
            updateSnapshots(transaction);
            pendingDelta -= extracted;
            return extracted;
        }
    }
}
