/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.lib.platform.storage;

import java.util.Objects;
import java.util.function.Predicate;
import javax.annotation.Nullable;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.fluid.FabricFluidVariants;
import buildcraft.lib.internal.transfer.FabricTransactionScope;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Fabric 26.3's active replacement for NeoForge PlatformStorage.
 *
 * <p>Native SlottedStorage keeps its exact slot semantics. General Fabric Storage
 * endpoints also work through legacy CapUtil paths with ephemeral view indices
 * and one insertion-only virtual slot, rather than being silently ignored.
 * Integrations that need stable slots should use API v2 ItemPort/FluidPort;
 * components and fluid mB granularity are preserved by the BCCE codecs.</p>
 */
public final class PlatformStorage {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;

    private PlatformStorage() {}

    @Nullable
    public static ItemStorage localInventory(@Nullable Container inventory) {
        return inventory == null ? null : new ContainerItems(inventory);
    }

    @Nullable
    public static ItemStorage items(@Nullable Level level, @Nullable BlockPos pos, @Nullable Direction face) {
        if (!valid(level, pos)) return null;
        Storage<ItemVariant> nativeStorage = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(
            level, pos, level.getBlockState(pos), level.getBlockEntity(pos), face);
        if (nativeStorage == null) return null;
        if (nativeStorage instanceof SlottedStorage<?> raw) {
            @SuppressWarnings("unchecked")
            SlottedStorage<ItemVariant> slotted = (SlottedStorage<ItemVariant>) raw;
            return new FabricSlottedItems(slotted);
        }
        return new FabricUnslottedItems(nativeStorage);
    }

    @Nullable
    public static ItemStorage items(@Nullable Entity entity, @Nullable Direction face) {
        if (entity == null || entity.isRemoved()) return null;
        // Fabric addons can expose their custom mobile inventories through an
        // entity lookup; vanilla chest boats, minecarts etc. still use Container.
        Storage<ItemVariant> nativeStorage = FabricEntityStorageLookups.ITEMS.find(entity, face);
        if (nativeStorage instanceof SlottedStorage<?> raw) {
            @SuppressWarnings("unchecked")
            SlottedStorage<ItemVariant> slotted = (SlottedStorage<ItemVariant>) raw;
            return new FabricSlottedItems(slotted);
        }
        if (nativeStorage != null) return new FabricUnslottedItems(nativeStorage);
        return entity instanceof Container container ? localInventory(container) : null;
    }

    @Nullable
    public static FluidStorage<FluidVolume> fluids(
        @Nullable Level level, @Nullable BlockPos pos, @Nullable Direction face
    ) {
        if (!valid(level, pos)) return null;
        Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> nativeStorage =
            net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.find(
                level, pos, level.getBlockState(pos), level.getBlockEntity(pos), face);
        if (nativeStorage == null) return null;
        return new FabricFluids(nativeStorage, level.registryAccess());
    }

    @Nullable
    public static EnergyStorage energy(@Nullable Level level, @Nullable BlockPos pos, @Nullable Direction face) {
        if (!valid(level, pos)) return null;
        team.reborn.energy.api.EnergyStorage nativeStorage =
            team.reborn.energy.api.EnergyStorage.SIDED.find(
                level, pos, level.getBlockState(pos), level.getBlockEntity(pos), face);
        return nativeStorage == null ? null : new FabricEnergy(nativeStorage);
    }

    @Nullable
    public static EnergyStorage energy(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        team.reborn.energy.api.EnergyStorage nativeStorage =
            ContainerItemContext.withConstant(stack).find(team.reborn.energy.api.EnergyStorage.ITEM);
        return nativeStorage == null ? null : new FabricEnergy(nativeStorage);
    }

    private static boolean valid(@Nullable Level level, @Nullable BlockPos pos) {
        return level != null && pos != null && level.hasChunkAt(pos);
    }

    private static int bounded(long number) {
        return number <= 0L ? 0 : (int) Math.min(number, Integer.MAX_VALUE);
    }

    private static ItemStack stackOf(ItemStack source, int count) {
        if (count <= 0) return ItemStack.EMPTY;
        ItemStack copy = source.copy();
        copy.setCount(count);
        return copy;
    }

    private static ItemStack remainder(ItemStack offered, long extracted) {
        int remaining = Math.max(0, offered.getCount() - bounded(extracted));
        return stackOf(offered, remaining);
    }

    private static final class ContainerItems implements MutableItemStorage {
        private final Container container;
        private ContainerItems(Container container) { this.container = Objects.requireNonNull(container); }

        private boolean validSlot(int slot) { return slot >= 0 && slot < container.getContainerSize(); }
        public int getSlots() { return container.getContainerSize(); }
        public ItemStack getStackInSlot(int slot) {
            return validSlot(slot) ? container.getItem(slot) : ItemStack.EMPTY;
        }
        public int getSlotLimit(int slot) {
            return validSlot(slot) ? container.getMaxStackSize() : 0;
        }
        public boolean isItemValid(int slot, ItemStack item) {
            return validSlot(slot) && item != null && !item.isEmpty() && container.canPlaceItem(slot, item);
        }
        public ItemStack insertItem(int slot, ItemStack offered, boolean simulate) {
            if (offered == null || offered.isEmpty()) return ItemStack.EMPTY;
            if (!isItemValid(slot, offered)) return offered;
            ItemStack current = container.getItem(slot);
            // In modern Minecraft, same item ID with different Data Components
            // is not the same stack (including damage and custom data).
            if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, offered)) return offered;
            int limit = Math.min(container.getMaxStackSize(), offered.getMaxStackSize());
            int moved = Math.min(offered.getCount(), Math.max(0, limit - current.getCount()));
            if (moved == 0) return offered;
            if (!simulate) {
                if (current.isEmpty()) container.setItem(slot, stackOf(offered, moved));
                else {
                    ItemStack changed = current.copy();
                    changed.grow(moved);
                    container.setItem(slot, changed);
                }
                container.setChanged();
            }
            return remainder(offered, moved);
        }
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (!validSlot(slot) || amount <= 0) return ItemStack.EMPTY;
            ItemStack current = container.getItem(slot);
            if (current.isEmpty()) return ItemStack.EMPTY;
            int count = Math.min(amount, current.getCount());
            if (!simulate) {
                ItemStack newStack = current.copy();
                newStack.shrink(count);
                container.setItem(slot, newStack);
                container.setChanged();
            }
            return stackOf(current, count);
        }
        public void setStackInSlot(int slot, ItemStack stack) {
            if (!validSlot(slot)) throw new IndexOutOfBoundsException("slot " + slot);
            container.setItem(slot, stack == null ? ItemStack.EMPTY : stack.copy());
            container.setChanged();
        }
    }

    private static final class FabricSlottedItems implements ItemStorage {
        private final SlottedStorage<ItemVariant> nativeStorage;
        private FabricSlottedItems(SlottedStorage<ItemVariant> nativeStorage) {
            this.nativeStorage = Objects.requireNonNull(nativeStorage);
        }
        @Nullable private SingleSlotStorage<ItemVariant> slot(int index) {
            return index >= 0 && index < nativeStorage.getSlotCount() ? nativeStorage.getSlot(index) : null;
        }
        public int getSlots() { return nativeStorage.getSlotCount(); }
        public ItemStack getStackInSlot(int index) {
            SingleSlotStorage<ItemVariant> slot = slot(index);
            return slot == null || slot.isResourceBlank() || slot.getAmount() <= 0
                ? ItemStack.EMPTY : slot.getResource().toStack(bounded(slot.getAmount()));
        }
        public int getSlotLimit(int index) {
            SingleSlotStorage<ItemVariant> slot = slot(index);
            return slot == null ? 0 : bounded(slot.getCapacity());
        }
        public boolean isItemValid(int index, ItemStack item) {
            SingleSlotStorage<ItemVariant> slot = slot(index);
            if (slot == null || item == null || item.isEmpty() || !slot.supportsInsertion()) return false;
            try (Transaction tx = FabricTransactionScope.begin()) {
                return slot.insert(ItemVariant.of(item), 1, tx) == 1;
            }
        }
        public ItemStack insertItem(int index, ItemStack stack, boolean simulate) {
            if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
            SingleSlotStorage<ItemVariant> slot = slot(index);
            if (slot == null) return stack;
            try (Transaction tx = FabricTransactionScope.begin()) {
                long inserted = Math.min(stack.getCount(), Math.max(0L,
                    slot.insert(ItemVariant.of(stack), stack.getCount(), tx)));
                if (!simulate && inserted > 0) tx.commit();
                return remainder(stack, inserted);
            }
        }
        public ItemStack extractItem(int index, int amount, boolean simulate) {
            SingleSlotStorage<ItemVariant> slot = slot(index);
            if (slot == null || amount <= 0 || slot.isResourceBlank()) return ItemStack.EMPTY;
            ItemVariant variant = slot.getResource();
            try (Transaction tx = FabricTransactionScope.begin()) {
                long extracted = Math.min(amount, Math.max(0L, slot.extract(variant, amount, tx)));
                if (!simulate && extracted > 0) tx.commit();
                return extracted <= 0 ? ItemStack.EMPTY : variant.toStack(bounded(extracted));
            }
        }
    }

    /**
     * Compatibility view of an unslotted Fabric Storage. Existing resource views
     * receive ephemeral read/extract indices; one extra empty index accepts items.
     * We never pretend these indices are persistent physical inventory slots.
     * Each mutation still uses the original Fabric transaction.
     */
    private static final class FabricUnslottedItems implements ItemStorage {
        private final Storage<ItemVariant> nativeStorage;

        private FabricUnslottedItems(Storage<ItemVariant> nativeStorage) {
            this.nativeStorage = Objects.requireNonNull(nativeStorage);
        }

        private StorageView<ItemVariant> view(int index) {
            if (index < 0) return null;
            int n = 0;
            for (StorageView<ItemVariant> view : nativeStorage) {
                if (n++ == index) return view;
            }
            return null;
        }

        public int getSlots() {
            int count = 1; // additional insertion endpoint even for empty storages
            for (StorageView<ItemVariant> ignored : nativeStorage) {
                if (count == Integer.MAX_VALUE) break;
                count++;
            }
            return count;
        }

        public ItemStack getStackInSlot(int index) {
            StorageView<ItemVariant> view = view(index);
            return view == null || view.isResourceBlank() || view.getAmount() <= 0
                ? ItemStack.EMPTY : view.getResource().toStack(bounded(view.getAmount()));
        }

        public int getSlotLimit(int index) {
            if (index < 0 || index >= getSlots()) return 0;
            StorageView<ItemVariant> view = view(index);
            return view == null ? 64 : bounded(view.getCapacity());
        }

        public boolean isItemValid(int index, ItemStack offered) {
            if (index < 0 || index >= getSlots() || offered == null || offered.isEmpty()) return false;
            try (Transaction tx = FabricTransactionScope.begin()) {
                return nativeStorage.insert(ItemVariant.of(offered), 1, tx) > 0;
            }
        }

        public ItemStack insertItem(int index, ItemStack offered, boolean simulate) {
            if (offered == null || offered.isEmpty()) return ItemStack.EMPTY;
            if (index < 0 || index >= getSlots()) return offered;
            try (Transaction tx = FabricTransactionScope.begin()) {
                long inserted = Math.min(offered.getCount(), Math.max(0L,
                    nativeStorage.insert(ItemVariant.of(offered), offered.getCount(), tx)));
                if (!simulate && inserted > 0) tx.commit();
                return remainder(offered, inserted);
            }
        }

        public ItemStack extractItem(int index, int amount, boolean simulate) {
            if (amount <= 0) return ItemStack.EMPTY;
            StorageView<ItemVariant> view = view(index);
            if (view == null || view.isResourceBlank() || view.getAmount() <= 0) return ItemStack.EMPTY;
            ItemVariant variant = view.getResource();
            try (Transaction tx = FabricTransactionScope.begin()) {
                long extracted = Math.min(amount, Math.max(0L, view.extract(variant, amount, tx)));
                if (!simulate && extracted > 0) tx.commit();
                return extracted <= 0 ? ItemStack.EMPTY : variant.toStack(bounded(extracted));
            }
        }
    }

    private static final class FabricFluids implements FilteredFluidStorage<FluidVolume> {
        private final Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> nativeStorage;
        private final net.minecraft.core.HolderLookup.Provider registries;
        private FabricFluids(
            Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> nativeStorage,
            net.minecraft.core.HolderLookup.Provider registries
        ) {
            this.nativeStorage = Objects.requireNonNull(nativeStorage);
            this.registries = Objects.requireNonNull(registries);
        }

        @Nullable
        private StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> slot(int index) {
            if (index < 0) return null;
            if (nativeStorage instanceof SlottedStorage<?> raw) {
                @SuppressWarnings("unchecked")
                SlottedStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> slotted =
                    (SlottedStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant>) raw;
                return index < slotted.getSlotCount() ? slotted.getSlot(index) : null;
            }
            int n = 0;
            for (StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view : nativeStorage) {
                if (n++ == index) return view;
            }
            return null;
        }
        private FluidVolume toVolume(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant nativeVariant,
                                     long droplets) {
            if (droplets < DROPLETS_PER_MB) return FluidVolume.empty();
            return FabricFluidVariants.toApi(nativeVariant, registries)
                .map(variant -> FluidVolume.of(variant, FluidAmount.of(droplets / DROPLETS_PER_MB)))
                .orElseGet(FluidVolume::empty);
        }
        public int getTanks() {
            if (nativeStorage instanceof SlottedStorage<?> slotted) return slotted.getSlotCount();
            // Generic Fabric storages do not promise physical slots. Their views
            // can all be extraction-only or entirely absent while insert() works.
            // Keep an additional virtual insertion endpoint, as for items.
            int count = 1;
            for (StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> ignored : nativeStorage) {
                if (count == Integer.MAX_VALUE) break;
                count++;
            }
            return count;
        }
        public FluidVolume getFluidInTank(int index) {
            StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view = slot(index);
            return view == null || view.isResourceBlank() ? FluidVolume.empty()
                : toVolume(view.getResource(), view.getAmount());
        }
        public int getTankCapacity(int index) {
            StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view = slot(index);
            if (view != null) return bounded(view.getCapacity() / DROPLETS_PER_MB);
            // An unslotted Storage cannot disclose its physical tank capacity;
            // the virtual endpoint only advertises a one-bucket probe capacity.
            // Actual limits are enforced by native insert() in fill()/isFluidValid().
            if (!(nativeStorage instanceof SlottedStorage<?>)
                && index >= 0 && index < getTanks()) return 1000;
            return 0;
        }
        public boolean isFluidValid(int index, FluidVolume volume) {
            StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view = slot(index);
            if (index < 0 || index >= getTanks() || volume == null || volume.isEmpty()) return false;
            var variant = FabricFluidVariants.fromApi(
                volume.requireVariant(), registries).orElse(null);
            if (variant == null) return false;
            try (Transaction tx = FabricTransactionScope.begin()) {
                if (view instanceof SingleSlotStorage<?> raw) {
                    @SuppressWarnings("unchecked")
                    SingleSlotStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> slot =
                        (SingleSlotStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant>) raw;
                    return slot.insert(variant, DROPLETS_PER_MB, tx) == DROPLETS_PER_MB;
                }
                // A generic Storage has no per-slot insertion semantics.
                return nativeStorage.insert(variant, DROPLETS_PER_MB, tx) == DROPLETS_PER_MB;
            }
        }

        private static long maxDroplets(long millibuckets) {
            if (millibuckets <= 0) return 0;
            return Math.min(millibuckets, Long.MAX_VALUE / DROPLETS_PER_MB) * DROPLETS_PER_MB;
        }
        private static long whole(long droplets) {
            return Math.max(0L, droplets) / DROPLETS_PER_MB * DROPLETS_PER_MB;
        }

        public int fill(FluidVolume fluid, boolean simulate) {
            if (fluid == null || fluid.isEmpty()) return 0;
            var nativeVariant = FabricFluidVariants.fromApi(
                fluid.requireVariant(), registries).orElse(null);
            if (nativeVariant == null) return 0;
            long offered = maxDroplets(fluid.amount().milliBuckets());
            if (offered <= 0) return 0;
            try (Transaction tx = FabricTransactionScope.begin()) {
                long available;
                try (Transaction probe = tx.openNested()) {
                    available = whole(nativeStorage.insert(nativeVariant, offered, probe));
                }
                if (available <= 0) return 0;
                long moved = nativeStorage.insert(nativeVariant, available, tx);
                if (moved != available) return 0; // abort partial/unsafe transaction
                if (!simulate) tx.commit();
                return bounded(moved / DROPLETS_PER_MB);
            }
        }

        private FluidVolume drainMatching(
            Predicate<FluidVolume> accepts, long maxMillibuckets, boolean simulate
        ) {
            long offered = maxDroplets(maxMillibuckets);
            if (offered <= 0) return FluidVolume.empty();
            for (StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view : nativeStorage) {
                if (view.isResourceBlank() || view.getAmount() < DROPLETS_PER_MB) continue;
                var nativeVariant = view.getResource();
                FluidVolume sample = toVolume(nativeVariant, DROPLETS_PER_MB);
                if (sample.isEmpty() || !accepts.test(sample)) continue;
                try (Transaction tx = FabricTransactionScope.begin()) {
                    long available;
                    try (Transaction probe = tx.openNested()) {
                        available = whole(view.extract(nativeVariant, offered, probe));
                    }
                    if (available <= 0) continue;
                    long moved = view.extract(nativeVariant, available, tx);
                    if (moved != available) continue; // abort partial transfer
                    if (!simulate) tx.commit();
                    return toVolume(nativeVariant, moved);
                }
            }
            return FluidVolume.empty();
        }
        public FluidVolume drain(FluidVolume fluid, boolean simulate) {
            if (fluid == null || fluid.isEmpty()) return FluidVolume.empty();
            return drainMatching(sample -> buildcraft.lib.fluid.FabricFluidCompatRegistry.areEquivalent(sample, fluid),
                fluid.amount().milliBuckets(), simulate);
        }
        public FluidVolume drain(int amount, boolean simulate) {
            return drainMatching(volume -> true, amount, simulate);
        }
        public FluidVolume drain(Predicate<FluidVolume> filter, int amount, boolean simulate) {
            return filter == null ? FluidVolume.empty() : drainMatching(filter, amount, simulate);
        }
    }

    private static final class FabricEnergy implements EnergyStorage {
        private final team.reborn.energy.api.EnergyStorage nativeStorage;
        private FabricEnergy(team.reborn.energy.api.EnergyStorage nativeStorage) {
            this.nativeStorage = Objects.requireNonNull(nativeStorage);
        }
        public int receiveEnergy(int amount, boolean simulate) {
            if (amount <= 0 || !canReceive()) return 0;
            try (Transaction tx = FabricTransactionScope.begin()) {
                long moved = Math.max(0L, Math.min(amount, nativeStorage.insert(amount, tx)));
                if (!simulate && moved > 0) tx.commit();
                return bounded(moved);
            }
        }
        public int extractEnergy(int amount, boolean simulate) {
            if (amount <= 0 || !canExtract()) return 0;
            try (Transaction tx = FabricTransactionScope.begin()) {
                long moved = Math.max(0L, Math.min(amount, nativeStorage.extract(amount, tx)));
                if (!simulate && moved > 0) tx.commit();
                return bounded(moved);
            }
        }
        public int getEnergyStored() { return bounded(nativeStorage.getAmount()); }
        public int getMaxEnergyStored() { return bounded(nativeStorage.getCapacity()); }
        public boolean canExtract() { return nativeStorage.supportsExtraction(); }
        public boolean canReceive() { return nativeStorage.supportsInsertion(); }
    }
}
