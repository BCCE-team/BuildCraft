package buildcraft.lib.platform.storage;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVolume;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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

    public static ItemStorage items(Object ignoredProvider, Direction face) {
        return null;
    }

    public static ItemStorage items(Level level, BlockPos pos, Direction face) {
        if (level == null || pos == null) return null;
        var state = level.getBlockState(pos);
        var blockEntity = level.getBlockEntity(pos);
        Storage<ItemVariant> storage = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, pos, state, blockEntity, face);
        return storage == null ? null : new FabricItems(storage);
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
        team.reborn.energy.api.EnergyStorage storage = team.reborn.energy.api.EnergyStorage.SIDED.find(level, pos, state, blockEntity, face);
        return storage == null ? null : new FabricEnergy(storage);
    }

    public static EnergyStorage pipeEnergy(Object holder, Direction face) {
        return null;
    }

    public static EnergyStorage energy(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        var context = net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext.withConstant(stack);
        team.reborn.energy.api.EnergyStorage storage = context.find(team.reborn.energy.api.EnergyStorage.ITEM);
        return storage == null ? null : new FabricEnergy(storage);
    }

    private static final class ContainerItems implements MutableItemStorage {
        private final Container container;
        private ContainerItems(Container container) { this.container = container; }
        @Override public int getSlots() { return container.getContainerSize(); }
        @Override public ItemStack getStackInSlot(int slot) { return container.getItem(slot); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !isItemValid(slot, stack)) return stack;
            ItemStack current = container.getItem(slot);
            int limit = Math.min(getSlotLimit(slot), stack.getMaxStackSize());
            if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, stack)) return stack;
            int space = limit - current.getCount();
            if (space <= 0) return stack;
            int moved = Math.min(space, stack.getCount());
            if (!simulate) {
                if (current.isEmpty()) container.setItem(slot, stack.copyWithCount(moved));
                else current.grow(moved);
                container.setChanged();
            }
            return stack.copyWithCount(stack.getCount() - moved);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack current = container.getItem(slot);
            if (current.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            int moved = Math.min(amount, current.getCount());
            ItemStack out = current.copyWithCount(moved);
            if (!simulate) {
                current.shrink(moved);
                if (current.isEmpty()) container.setItem(slot, ItemStack.EMPTY);
                container.setChanged();
            }
            return out;
        }
        @Override public int getSlotLimit(int slot) { return container.getMaxStackSize(); }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return container.canPlaceItem(slot, stack); }
        @Override public void setStackInSlot(int slot, ItemStack stack) { container.setItem(slot, stack); container.setChanged(); }
    }

    private static final class FabricItems implements ItemStorage {
        private final Storage<ItemVariant> storage;
        private FabricItems(Storage<ItemVariant> storage) { this.storage = storage; }
        private List<StorageView<ItemVariant>> views() {
            List<StorageView<ItemVariant>> list = new ArrayList<>();
            for (StorageView<ItemVariant> view : storage) list.add(view);
            return list;
        }
        @Override public int getSlots() { return views().size(); }
        @Override public ItemStack getStackInSlot(int slot) {
            List<StorageView<ItemVariant>> views = views();
            if (slot < 0 || slot >= views.size()) return ItemStack.EMPTY;
            StorageView<ItemVariant> view = views.get(slot);
            return view.isResourceBlank() ? ItemStack.EMPTY : view.getResource().toStack((int) Math.min(Integer.MAX_VALUE, view.getAmount()));
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            long moved;
            try (Transaction tx = Transaction.openOuter()) {
                moved = storage.insert(ItemVariant.of(stack), stack.getCount(), tx);
                if (!simulate) tx.commit();
            }
            int remaining = Math.max(0, stack.getCount() - (int) Math.min(Integer.MAX_VALUE, moved));
            return remaining == 0 ? ItemStack.EMPTY : stack.copyWithCount(remaining);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack current = getStackInSlot(slot);
            if (current.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            long moved;
            try (Transaction tx = Transaction.openOuter()) {
                moved = storage.extract(ItemVariant.of(current), amount, tx);
                if (!simulate) tx.commit();
            }
            return moved <= 0 ? ItemStack.EMPTY : current.copyWithCount((int) Math.min(Integer.MAX_VALUE, moved));
        }
        @Override public int getSlotLimit(int slot) {
            List<StorageView<ItemVariant>> views = views();
            return slot < 0 || slot >= views.size() ? 64 : (int) Math.min(Integer.MAX_VALUE, views.get(slot).getCapacity());
        }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty(); }
    }

    private static final class FabricFluids implements FluidStorage<FluidVolume> {
        private final Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage;
        private FabricFluids(Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage) { this.storage = storage; }
        private List<StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant>> views() {
            List<StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant>> list = new ArrayList<>();
            for (var view : storage) list.add(view);
            return list;
        }
        @Override public int getTanks() { return views().size(); }
        @Override public FluidVolume getFluidInTank(int tank) {
            var views = views();
            if (tank < 0 || tank >= views.size()) return FluidVolume.empty();
            var view = views.get(tank);
            return view.isResourceBlank() ? FluidVolume.empty() : toVolume(view.getResource(), view.getAmount());
        }
        @Override public int getTankCapacity(int tank) {
            var views = views();
            return tank < 0 || tank >= views.size() ? 0 : mb(views.get(tank).getCapacity());
        }
        @Override public boolean isFluidValid(int tank, FluidVolume fluid) { return fluid != null && !fluid.isEmpty(); }
        @Override public int fill(FluidVolume fluid, boolean simulate) {
            if (fluid == null || fluid.isEmpty()) return 0;
            var variant = fromVolume(fluid);
            long moved;
            try (Transaction tx = Transaction.openOuter()) {
                moved = storage.insert(variant, droplets(fluid.amount().milliBuckets()), tx);
                if (!simulate) tx.commit();
            }
            return mb(moved);
        }
        @Override public FluidVolume drain(FluidVolume fluid, boolean simulate) {
            if (fluid == null || fluid.isEmpty()) return FluidVolume.empty();
            var variant = fromVolume(fluid);
            long moved;
            try (Transaction tx = Transaction.openOuter()) {
                moved = storage.extract(variant, droplets(fluid.amount().milliBuckets()), tx);
                if (!simulate) tx.commit();
            }
            return moved <= 0 ? FluidVolume.empty() : toVolume(variant, moved);
        }
        @Override public FluidVolume drain(int amount, boolean simulate) {
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
        private static FluidVolume toVolume(net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant variant, long droplets) {
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
        private FabricEnergy(team.reborn.energy.api.EnergyStorage storage) { this.storage = storage; }
        @Override public int receiveEnergy(int amount, boolean simulate) {
            try (Transaction tx = Transaction.openOuter()) {
                long moved = storage.insert(Math.max(0, amount), tx);
                if (!simulate) tx.commit();
                return saturating(moved);
            }
        }
        @Override public int extractEnergy(int amount, boolean simulate) {
            try (Transaction tx = Transaction.openOuter()) {
                long moved = storage.extract(Math.max(0, amount), tx);
                if (!simulate) tx.commit();
                return saturating(moved);
            }
        }
        @Override public int getEnergyStored() { return saturating(storage.getAmount()); }
        @Override public int getMaxEnergyStored() { return saturating(storage.getCapacity()); }
        @Override public boolean canExtract() { return storage.supportsExtraction(); }
        @Override public boolean canReceive() { return storage.supportsInsertion(); }
    }

    private static long droplets(long mb) { return Math.max(0, mb) * DROPLETS_PER_MB; }
    private static int mb(long droplets) { return saturating(droplets / DROPLETS_PER_MB); }
    private static int saturating(long value) { return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value); }
}
