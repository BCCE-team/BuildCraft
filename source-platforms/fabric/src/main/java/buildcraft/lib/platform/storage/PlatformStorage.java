package buildcraft.lib.platform.storage;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.lib.internal.transfer.FabricEnergyTransferAccess;
import buildcraft.lib.internal.transfer.FabricFluidTransferAccess;
import buildcraft.lib.internal.transfer.FabricFluidVariants;
import buildcraft.lib.internal.transfer.FabricTransferTransactions;
import buildcraft.lib.internal.transfer.EnergyTransferAccess;
import buildcraft.lib.internal.transfer.FluidTransferAccess;
import buildcraft.lib.internal.transfer.ItemTransferAccess;
import buildcraft.lib.internal.transfer.OperationScope;
import java.util.Objects;
import java.util.function.Predicate;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Fabric Transfer API bridge for loader-neutral BuildCraft storage views. */
public final class PlatformStorage {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;

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

    /**
     * Returns an indexed tank view only when the native endpoint explicitly implements Fabric's SlottedStorage.
     * Generic Storage<FluidVariant> endpoints stay slotless and are exposed through {@link #fluidTransfer}.
     */
    public static FluidStorage<FluidVolume> fluids(Level level, BlockPos pos, Direction face) {
        Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage = findFluids(level, pos, face);
        if (!(storage instanceof SlottedStorage<?> rawSlotted)) return null;
        @SuppressWarnings("unchecked")
        SlottedStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> slotted =
            (SlottedStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant>) rawSlotted;
        return new FabricSlottedFluids(slotted);
    }

    /** Slotless Fabric fluid endpoint used by API v2 and gameplay transfer operations. */
    public static FluidTransferAccess fluidTransfer(Level level, BlockPos pos, Direction face) {
        Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage = findFluids(level, pos, face);
        return storage == null ? null : new FabricFluidTransferAccess(storage);
    }

    private static Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> findFluids(
        Level level,
        BlockPos pos,
        Direction face
    ) {
        if (level == null || pos == null) return null;
        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        return net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage.SIDED.find(
            level, pos, state, blockEntity, face
        );
    }

    public static FluidStorage<FluidVolume> pipeFluids(Object holder, Direction face) {
        return null;
    }

    public static EnergyStorage energy(Object ignoredProvider, Direction face) {
        return null;
    }

    public static EnergyStorage energy(Level level, BlockPos pos, Direction face) {
        team.reborn.energy.api.EnergyStorage storage = findEnergy(level, pos, face);
        return storage == null ? null : new FabricEnergy(storage);
    }

    /** Transaction-native external-energy endpoint used by API v2, MJ conversion and energy gameplay. */
    public static EnergyTransferAccess energyTransfer(Level level, BlockPos pos, Direction face) {
        team.reborn.energy.api.EnergyStorage storage = findEnergy(level, pos, face);
        return storage == null ? null : new FabricEnergyTransferAccess(storage);
    }

    private static team.reborn.energy.api.EnergyStorage findEnergy(Level level, BlockPos pos, Direction face) {
        if (level == null || pos == null) return null;
        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        return team.reborn.energy.api.EnergyStorage.SIDED.find(level, pos, state, blockEntity, face);
    }

    public static EnergyStorage pipeEnergy(Object holder, Direction face) {
        return null;
    }

    public static EnergyStorage energy(ItemStack stack) {
        team.reborn.energy.api.EnergyStorage storage = findEnergy(stack);
        return storage == null ? null : new FabricEnergy(storage);
    }

    /** Item-form transaction-native external-energy endpoint for charging/discharging gameplay. */
    public static EnergyTransferAccess energyTransfer(ItemStack stack) {
        team.reborn.energy.api.EnergyStorage storage = findEnergy(stack);
        return storage == null ? null : new FabricEnergyTransferAccess(storage);
    }

    private static team.reborn.energy.api.EnergyStorage findEnergy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ContainerItemContext context = ContainerItemContext.withConstant(stack);
        return context.find(team.reborn.energy.api.EnergyStorage.ITEM);
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
                return FabricTransferTransactions.with(scope, transaction -> {
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
                return FabricTransferTransactions.with(scope, transaction -> {
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

    /** Indexed tank adapter used only when Fabric exposes stable native fluid slots. */
    private static final class FabricSlottedFluids implements FilteredFluidStorage<FluidVolume> {
        private final SlottedStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> slotted;
        private final FabricFluidTransferAccess transfer;

        private FabricSlottedFluids(
            SlottedStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> slotted
        ) {
            this.slotted = slotted;
            this.transfer = new FabricFluidTransferAccess(slotted);
        }

        @Override
        public int getTanks() {
            return slotted.getSlotCount();
        }

        @Override
        public FluidVolume getFluidInTank(int tank) {
            SingleSlotStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view = tank(tank);
            if (view == null || view.isResourceBlank() || view.getAmount() < DROPLETS_PER_MB) {
                return FluidVolume.empty();
            }
            return FluidVolume.of(
                FabricFluidVariants.toApi(view.getResource()),
                view.getAmount() / DROPLETS_PER_MB
            );
        }

        @Override
        public int getTankCapacity(int tank) {
            SingleSlotStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view = tank(tank);
            return view == null ? 0 : saturating(view.getCapacity() / DROPLETS_PER_MB);
        }

        @Override
        public boolean isFluidValid(int tank, FluidVolume fluid) {
            SingleSlotStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> target = tank(tank);
            if (target == null || fluid == null || fluid.isEmpty() || !target.supportsInsertion()) return false;
            var nativeVariant = FabricFluidVariants.fromApi(fluid.requireVariant()).orElse(null);
            if (nativeVariant == null) return false;
            try (Transaction transaction = Transaction.openOuter()) {
                return target.insert(nativeVariant, DROPLETS_PER_MB, transaction) >= DROPLETS_PER_MB;
            }
        }

        @Override
        public int fill(FluidVolume fluid, boolean simulate) {
            if (fluid == null || fluid.isEmpty()) return 0;
            try (OperationScope scope = OperationScope.open(simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE)) {
                return saturating(transfer.insert(fluid, scope).transferredAmount().milliBuckets());
            }
        }

        @Override
        public FluidVolume drain(FluidVolume fluid, boolean simulate) {
            if (fluid == null || fluid.isEmpty()) return FluidVolume.empty();
            try (OperationScope scope = OperationScope.open(simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE)) {
                return transfer.extract(
                    FluidMatcher.exact(fluid.requireVariant()), fluid.amount(), scope
                ).transferred();
            }
        }

        @Override
        public FluidVolume drain(int amount, boolean simulate) {
            if (amount <= 0) return FluidVolume.empty();
            try (OperationScope scope = OperationScope.open(simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE)) {
                return transfer.extract(FluidMatcher.any(), buildcraft.api.v2.fluid.FluidAmount.of(amount), scope)
                    .transferred();
            }
        }

        @Override
        public FluidVolume drain(Predicate<FluidVolume> filter, int amount, boolean simulate) {
            if (filter == null || amount <= 0) return FluidVolume.empty();
            FluidMatcher matcher = (variant, context) -> filter.test(FluidVolume.of(variant, 1));
            try (OperationScope scope = OperationScope.open(simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE)) {
                return transfer.extract(matcher, buildcraft.api.v2.fluid.FluidAmount.of(amount), scope).transferred();
            }
        }

        private SingleSlotStorage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> tank(int tank) {
            return tank < 0 || tank >= slotted.getSlotCount() ? null : slotted.getSlot(tank);
        }
    }

    private static final class FabricEnergy implements EnergyStorage {
        private final FabricEnergyTransferAccess transfer;

        private FabricEnergy(team.reborn.energy.api.EnergyStorage storage) {
            this.transfer = new FabricEnergyTransferAccess(storage);
        }

        @Override
        public int receiveEnergy(int amount, boolean simulate) {
            if (amount <= 0) return 0;
            try (OperationScope scope = OperationScope.open(simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE)) {
                return saturating(transfer.insert(amount, scope));
            }
        }

        @Override
        public int extractEnergy(int amount, boolean simulate) {
            if (amount <= 0) return 0;
            try (OperationScope scope = OperationScope.open(simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE)) {
                return saturating(transfer.extract(amount, scope));
            }
        }

        @Override
        public int getEnergyStored() {
            return saturating(transfer.stored());
        }

        @Override
        public int getMaxEnergyStored() {
            return saturating(transfer.capacity());
        }

        @Override
        public boolean canExtract() {
            return transfer.canExtract();
        }

        @Override
        public boolean canReceive() {
            return transfer.canInsert();
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

    private static int saturating(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }
}
