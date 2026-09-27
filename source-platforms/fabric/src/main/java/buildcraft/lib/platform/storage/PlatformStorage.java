package buildcraft.lib.platform.storage;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.lib.internal.transfer.ItemTransferAccess;
import buildcraft.lib.internal.transfer.OperationScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Fabric Transfer API bridge for loader-neutral BuildCraft storage views. */
public final class PlatformStorage {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;
    private static final Object ITEM_TRANSACTION_KEY = new Object();

    private PlatformStorage() { }

    public static ItemStorage localInventory(Container inventory) {
        return inventory == null ? null : new ContainerItems(inventory);
    }

    /**
     * Provider-only lookup has no Fabric equivalent: Transfer API storage lookup is world/position based.
     * Callers that have a world position must use {@link #items(Level, BlockPos, Direction)}.
     */
    public static ItemStorage items(Object ignoredProvider, Direction face) {
        return null;
    }

    /**
     * Returns a slot view only when the native Fabric storage really exposes stable indexed slots.
     * Generic {@code Storage<ItemVariant>} endpoints deliberately return {@code null} here rather than fabricating
     * slot numbers that insertion would not honour.
     */
    public static ItemStorage items(Level level, BlockPos pos, Direction face) {
        Storage<ItemVariant> storage = findItems(level, pos, face);
        if (!(storage instanceof SlottedStorage<?> rawSlotted)) return null;
        @SuppressWarnings("unchecked")
        SlottedStorage<ItemVariant> slotted = (SlottedStorage<ItemVariant>) rawSlotted;
        return new FabricSlottedItems(slotted);
    }

    /** Slotless Fabric item endpoint used by API v2 and later gameplay transfer adapters. */
    public static ItemTransferAccess itemTransfer(Level level, BlockPos pos, Direction face) {
        Storage<ItemVariant> storage = findItems(level, pos, face);
        return storage == null ? null : new FabricTransferItems(storage);
    }

    private static Storage<ItemVariant> findItems(Level level, BlockPos pos, Direction face) {
        if (level == null || pos == null) return null;
        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        return net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, pos, state, blockEntity, face);
    }

    public static FluidStorage<FluidVolume> fluids(Object ignoredProvider, Direction face) {
        return null;
    }

    public static FluidStorage<FluidVolume> fluids(Level level, BlockPos pos, Direction face) {
        if (level == null || pos == null) return null;
        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage =
            net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.find(level, pos, state, blockEntity, face);
        return storage == null ? null : new FabricFluids(storage);
    }

    public static FluidStorage<FluidVolume> pipeFluids(Object holder, Direction face) {
        return null;
    }

    public static EnergyStorage energy(Object ignoredProvider, Direction face) {
        return null;
    }

    public static EnergyStorage energy(Level level, BlockPos pos, Direction face) {
        if (level == null || pos == null) return null;
        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        team.reborn.energy.api.EnergyStorage storage =
            team.reborn.energy.api.EnergyStorage.SIDED.find(level, pos, state, blockEntity, face);
        return storage == null ? null : new FabricEnergy(storage);
    }

    public static EnergyStorage pipeEnergy(Object holder, Direction face) {
        return null;
    }

    public static EnergyStorage energy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ContainerItemContext context = ContainerItemContext.withConstant(stack);
        team.reborn.energy.api.EnergyStorage storage = context.find(team.reborn.energy.api.EnergyStorage.ITEM);
        return storage == null ? null : new FabricEnergy(storage);
    }

    private static final class ContainerItems implements MutableItemStorage {
        private final Container container;

        private ContainerItems(Container container) {
            this.container = container;
        }

        @Override
        public int getSlots() {
            return container.getContainerSize();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return validSlot(slot) ? container.getItem(slot) : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!validSlot(slot) || stack == null || stack.isEmpty() || !isItemValid(slot, stack)) return stack;
            ItemStack current = container.getItem(slot);
            int limit = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, stack)) return stack;
            int space = limit - current.getCount();
            if (space <= 0) return stack;
            int moved = Math.min(space, stack.getCount());
            if (!simulate) {
                if (current.isEmpty()) container.setItem(slot, copyWithCount(stack, moved));
                else current.grow(moved);
                container.setChanged();
            }
            return remainder(stack, moved);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (!validSlot(slot) || amount <= 0) return ItemStack.EMPTY;
            ItemStack current = container.getItem(slot);
            if (current.isEmpty()) return ItemStack.EMPTY;
            int moved = Math.min(amount, current.getCount());
            ItemStack out = copyWithCount(current, moved);
            if (!simulate) {
                current.shrink(moved);
                if (current.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                container.setChanged();
            }
            return out;
        }

        @Override
        public int getSlotLimit(int slot) {
            return validSlot(slot) ? container.getMaxStackSize() : 0;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return validSlot(slot) && stack != null && !stack.isEmpty() && container.canPlaceItem(slot, stack);
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            if (!validSlot(slot)) throw new IndexOutOfBoundsException("Slot index out of range: " + slot);
            container.setItem(slot, stack == null ? ItemStack.EMPTY : stack);
            container.setChanged();
        }

        private boolean validSlot(int slot) {
            return slot >= 0 && slot < container.getContainerSize();
        }
    }

    /** Slotless adapter for arbitrary Fabric item storages. */
    private static class FabricTransferItems implements ItemTransferAccess {
        protected final Storage<ItemVariant> storage;

        private FabricTransferItems(Storage<ItemVariant> storage) {
            this.storage = Objects.requireNonNull(storage, "storage");
        }

        @Override
        public ItemTransferResult insert(ItemStack offered, OperationScope scope) {
            int requested = offered == null ? 0 : offered.getCount();
            if (offered == null || offered.isEmpty()) return ItemTransferResult.nothing(requested);
            Objects.requireNonNull(scope, "scope");
            try (OperationScope.Guard guard = scope.enter(storage)) {
                if (!guard.entered()) return ItemTransferResult.nothing(requested);
                return withItemTransaction(scope, transaction -> {
                    long inserted = storage.insert(ItemVariant.of(offered), requested, transaction);
                    int accepted = (int) Math.min((long) requested, Math.max(0L, inserted));
                    return ItemTransferResult.ofInsertion(offered, accepted);
                });
            }
        }

        @Override
        public ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationScope scope) {
            if (matcher == null || maxCount <= 0) {
                return ItemTransferResult.nothing(Math.max(0, maxCount));
            }
            Objects.requireNonNull(scope, "scope");
            try (OperationScope.Guard guard = scope.enter(storage)) {
                if (!guard.entered()) return ItemTransferResult.nothing(maxCount);
                return withItemTransaction(scope, transaction -> {
                    for (StorageView<ItemVariant> view : storage) {
                        if (view.isResourceBlank() || view.getAmount() <= 0) continue;
                        ItemVariant variant = view.getResource();
                        ItemStack sample = variant.toStack(1);
                        if (!matcher.matches(sample)) continue;

                        long extracted = storage.extract(variant, maxCount, transaction);
                        if (extracted <= 0) continue;

                        int count = (int) Math.min((long) Integer.MAX_VALUE, extracted);
                        return ItemTransferResult.ofExtraction(maxCount, variant.toStack(count));
                    }
                    return ItemTransferResult.nothing(maxCount);
                });
            }
        }
    }

    /** Indexed adapter used only for native storages that explicitly implement Fabric's SlottedStorage contract. */
    private static final class FabricSlottedItems extends FabricTransferItems implements ItemStorage {
        private final SlottedStorage<ItemVariant> slotted;

        private FabricSlottedItems(SlottedStorage<ItemVariant> slotted) {
            super(slotted);
            this.slotted = slotted;
        }

        @Override
        public int getSlots() {
            return slotted.getSlotCount();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            SingleSlotStorage<ItemVariant> view = slot(slot);
            if (view == null || view.isResourceBlank() || view.getAmount() <= 0) return ItemStack.EMPTY;
            return view.getResource().toStack((int) Math.min((long) Integer.MAX_VALUE, view.getAmount()));
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            SingleSlotStorage<ItemVariant> target = slot(slot);
            if (target == null || stack == null || stack.isEmpty()) return stack;
            long moved;
            try (Transaction transaction = Transaction.openOuter()) {
                moved = target.insert(ItemVariant.of(stack), stack.getCount(), transaction);
                if (!simulate) transaction.commit();
            }
            return remainder(stack, (int) Math.min((long) stack.getCount(), Math.max(0L, moved)));
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            SingleSlotStorage<ItemVariant> target = slot(slot);
            if (target == null || amount <= 0 || target.isResourceBlank()) return ItemStack.EMPTY;
            ItemVariant variant = target.getResource();
            long moved;
            try (Transaction transaction = Transaction.openOuter()) {
                moved = target.extract(variant, amount, transaction);
                if (!simulate) transaction.commit();
            }
            return moved <= 0 ? ItemStack.EMPTY
                : variant.toStack((int) Math.min((long) Integer.MAX_VALUE, moved));
        }

        @Override
        public int getSlotLimit(int slot) {
            SingleSlotStorage<ItemVariant> target = slot(slot);
            return target == null ? 0 : saturating(target.getCapacity());
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            SingleSlotStorage<ItemVariant> target = slot(slot);
            if (target == null || stack == null || stack.isEmpty() || !target.supportsInsertion()) return false;
            try (Transaction transaction = Transaction.openOuter()) {
                return target.insert(ItemVariant.of(stack), 1, transaction) > 0;
            }
        }

        private SingleSlotStorage<ItemVariant> slot(int slot) {
            return slot < 0 || slot >= slotted.getSlotCount() ? null : slotted.getSlot(slot);
        }
    }

    /**
     * Reuses one outer Fabric transaction for the entire root BuildCraft operation.
     * A nested SIMULATE scope under an executing root receives an aborted nested transaction so it cannot leak
     * simulated mutations into the eventual root commit.
     */
    private static <T> T withItemTransaction(OperationScope scope, Function<TransactionContext, T> action) {
        Objects.requireNonNull(action, "action");
        Transaction root = scope.sharedAttachment(ITEM_TRANSACTION_KEY, () -> {
            Transaction transaction = Transaction.openOuter();
            scope.onRootClose(commit -> {
                if (commit) transaction.commit();
                else transaction.abort();
            });
            return transaction;
        });

        if (scope.simulate() && scope.rootMode() == OperationMode.EXECUTE) {
            try (Transaction nested = Transaction.openNested(root)) {
                try {
                    return action.apply(nested);
                } catch (RuntimeException | Error failure) {
                    scope.markFailed();
                    throw failure;
                }
            }
        }

        try {
            return action.apply(root);
        } catch (RuntimeException | Error failure) {
            scope.markFailed();
            throw failure;
        }
    }

    private static final class FabricFluids implements FluidStorage<FluidVolume> {
        private final Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage;

        private FabricFluids(Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage) {
            this.storage = storage;
        }

        private List<StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant>> views() {
            List<StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant>> list = new ArrayList<>();
            for (var view : storage) list.add(view);
            return list;
        }

        @Override
        public int getTanks() {
            return views().size();
        }

        @Override
        public FluidVolume getFluidInTank(int tank) {
            var views = views();
            if (tank < 0 || tank >= views.size()) return FluidVolume.empty();
            var view = views.get(tank);
            return view.isResourceBlank() ? FluidVolume.empty() : toVolume(view.getResource(), view.getAmount());
        }

        @Override
        public int getTankCapacity(int tank) {
            var views = views();
            return tank < 0 || tank >= views.size() ? 0 : mb(views.get(tank).getCapacity());
        }

        @Override
        public boolean isFluidValid(int tank, FluidVolume fluid) {
            return fluid != null && !fluid.isEmpty();
        }

        @Override
        public int fill(FluidVolume fluid, boolean simulate) {
            if (fluid == null || fluid.isEmpty()) return 0;
            var variant = fromVolume(fluid);
            long moved;
            try (Transaction tx = Transaction.openOuter()) {
                moved = storage.insert(variant, droplets(fluid.amount().milliBuckets()), tx);
                if (!simulate) tx.commit();
            }
            return mb(moved);
        }

        @Override
        public FluidVolume drain(FluidVolume fluid, boolean simulate) {
            if (fluid == null || fluid.isEmpty()) return FluidVolume.empty();
            var variant = fromVolume(fluid);
            long moved;
            try (Transaction tx = Transaction.openOuter()) {
                moved = storage.extract(variant, droplets(fluid.amount().milliBuckets()), tx);
                if (!simulate) tx.commit();
            }
            return moved <= 0 ? FluidVolume.empty() : toVolume(variant, moved);
        }

        @Override
        public FluidVolume drain(int amount, boolean simulate) {
            if (amount <= 0) return FluidVolume.empty();
            for (var view : views()) {
                if (view.isResourceBlank()) continue;
                var variant = view.getResource();
                long moved;
                try (Transaction tx = Transaction.openOuter()) {
                    moved = storage.extract(variant, droplets(amount), tx);
                    if (!simulate) tx.commit();
                }
                if (moved > 0) return toVolume(variant, moved);
            }
            return FluidVolume.empty();
        }

        private static FluidVolume toVolume(
            net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant variant,
            long droplets
        ) {
            var id = BuiltInRegistries.FLUID.getKey(variant.getFluid());
            return FluidVolume.of(buildcraft.api.v2.fluid.FluidVariant.of(id), mb(droplets));
        }

        private static net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant fromVolume(FluidVolume volume) {
            var fluid = BuiltInRegistries.FLUID.get(volume.requireVariant().fluidId());
            return net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant.of(fluid);
        }
    }

    private static final class FabricEnergy implements EnergyStorage {
        private final team.reborn.energy.api.EnergyStorage storage;

        private FabricEnergy(team.reborn.energy.api.EnergyStorage storage) {
            this.storage = storage;
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            try (Transaction tx = Transaction.openOuter()) {
                long moved = storage.insert(Math.max(0, amount), tx);
                if (!simulate) tx.commit();
                return saturating(moved);
            }
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            try (Transaction tx = Transaction.openOuter()) {
                long moved = storage.extract(Math.max(0, amount), tx);
                if (!simulate) tx.commit();
                return saturating(moved);
            }
        }

        @Override
        public int getEnergyStored() {
            return saturating(storage.getAmount());
        }

        @Override
        public int getMaxEnergyStored() {
            return saturating(storage.getCapacity());
        }

        @Override
        public boolean canExtract() {
            return storage.supportsExtraction();
        }

        @Override
        public boolean canReceive() {
            return storage.supportsInsertion();
        }
    }

    private static ItemStack remainder(ItemStack stack, int moved) {
        int remaining = Math.max(0, stack.getCount() - Math.max(0, moved));
        return remaining == 0 ? ItemStack.EMPTY : copyWithCount(stack, remaining);
    }

    private static ItemStack copyWithCount(ItemStack stack, int count) {
        ItemStack copy = stack.copy();
        copy.setCount(count);
        return copy;
    }

    private static long droplets(long mb) {
        return Math.max(0, mb) * DROPLETS_PER_MB;
    }

    private static int mb(long droplets) {
        return saturating(droplets / DROPLETS_PER_MB);
    }

    private static int saturating(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }
}
