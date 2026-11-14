package buildcraft.lib.internal.api.v2.platform;

import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidMatcher;
import buildcraft.api.v2.fluid.FluidMatchContext;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fluid.FluidTransferResult;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.api.v2.item.ItemMatcher;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.item.SlottedItemPort;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.platform.EnergyTransfer;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.api.v2.platform.FluidTransfer;
import buildcraft.api.v2.platform.ItemTransfer;
import buildcraft.api.v2.platform.PlatformServices;
import buildcraft.lib.internal.api.v2.BuildCraftApiRuntime;
import buildcraft.lib.internal.transfer.FabricTransactionScope;
import buildcraft.lib.fluid.FabricFluidVariants;
import buildcraft.lib.internal.capabilities.IBCCapabilityProvider;
import buildcraft.lib.internal.inventory.IItemHandlerFiltered;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.platform.storage.FabricSlotFilterProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.HolderLookup;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import team.reborn.energy.api.EnergyStorage;

/**
 * API v2 on Fabric 26.3. Uses native transactions; shared BCCE transfer
 * results, matching and balance are never redefined here.
 *
 * Fluid transfers with component-bearing Fabric variants are explicitly
 * rejected until their component codec is lossless. Stripping components
 * would violate API v2 and corrupt potion/chemical/modded fluids.
 */
public final class PlatformApi2Bootstrap {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;
    private static final FluidMatchContext FLUID_MATCH_CONTEXT = (fluidId, tagId) -> {
        Fluid fluid = BuiltInRegistries.FLUID.getOptional(fluidId).orElse(Fluids.EMPTY);
        return fluid != Fluids.EMPTY && fluid.is(TagKey.create(Registries.FLUID, tagId));
    };
    private static boolean installed;

    private PlatformApi2Bootstrap() {}

    public static synchronized void install() {
        if (installed) return;
        if (BuildCraftApiRuntime.INSTANCE.service(BuildCraftServices.PLATFORM).isEmpty()) {
            BuildCraftApiRuntime.INSTANCE.installService(BuildCraftServices.PLATFORM, Services.INSTANCE);
        }
        installed = true;
    }

    private enum Services implements PlatformServices {
        INSTANCE;
        public Optional<ItemTransfer> itemTransfer() { return Optional.of(Items.INSTANCE); }
        public Optional<FluidTransfer> fluidTransfer() { return Optional.of(FluidsPort.INSTANCE); }
        public Optional<EnergyTransfer> energyTransfer() { return Optional.of(Energy.INSTANCE); }
    }

    private static boolean valid(Level level, BlockPos pos) {
        return level != null && pos != null && level.hasChunkAt(pos);
    }

    private enum Items implements ItemTransfer {
        INSTANCE;
        public Optional<ItemPort> find(Level level, BlockPos pos, Direction side) {
            if (!valid(level, pos)) return Optional.empty();
            var state = level.getBlockState(pos);
            var blockEntity = level.getBlockEntity(pos);
            Storage<ItemVariant> nativeStorage = ItemStorage.SIDED.find(
                level, pos, state, blockEntity, side);
            if (nativeStorage == null) return Optional.empty();
            if (nativeStorage instanceof SlottedStorage<?> slotted) {
                @SuppressWarnings("unchecked")
                SlottedStorage<ItemVariant> slots = (SlottedStorage<ItemVariant>) slotted;
                return Optional.of(new SlottedItems(slots, blockEntity, side));
            }
            return Optional.of(new NativeItems(nativeStorage));
        }
    }

    private static class NativeItems implements ItemPort {
        final Storage<ItemVariant> storage;

        NativeItems(Storage<ItemVariant> storage) { this.storage = storage; }

        public ItemTransferResult insert(ItemStack offered, OperationMode mode) {
            if (offered == null || offered.isEmpty()) {
                return ItemTransferResult.nothing(offered == null ? 0 : offered.getCount());
            }
            try (Transaction transaction = FabricTransactionScope.begin()) {
                long count = Math.max(0L, storage.insert(ItemVariant.of(offered), offered.getCount(), transaction));
                count = Math.min(count, offered.getCount());
                if (mode == OperationMode.EXECUTE && count > 0) transaction.commit();
                return ItemTransferResult.ofInsertion(offered, (int) count);
            }
        }

        public ItemTransferResult extract(ItemMatcher matcher, int maxCount, OperationMode mode) {
            if (matcher == null || maxCount <= 0) return ItemTransferResult.nothing(Math.max(0, maxCount));
            for (StorageView<ItemVariant> view : storage) {
                if (view.isResourceBlank() || view.getAmount() <= 0) continue;
                ItemVariant variant = view.getResource();
                if (!matcher.matches(variant.toStack(1))) continue;
                try (Transaction transaction = FabricTransactionScope.begin()) {
                    long count = Math.max(0L, view.extract(variant, maxCount, transaction));
                    count = Math.min(count, maxCount);
                    if (count == 0) continue;
                    ItemStack extracted = variant.toStack((int) count);
                    if (mode == OperationMode.EXECUTE) transaction.commit();
                    return ItemTransferResult.ofExtraction(maxCount, extracted);
                }
            }
            return ItemTransferResult.nothing(maxCount);
        }
    }

    private static final class SlottedItems extends NativeItems implements SlottedItemPort {
        private final SlottedStorage<ItemVariant> slots;
        private final BlockEntity owner;
        private final Direction side;

        SlottedItems(SlottedStorage<ItemVariant> slots, BlockEntity owner, Direction side) {
            super(slots);
            this.slots = slots;
            this.owner = owner;
            this.side = side;
        }

        public int slots() { return slots.getSlotCount(); }

        public ItemStack stackInSlot(int slot) {
            StorageView<ItemVariant> view = slots.getSlot(slot);
            return view.isResourceBlank() || view.getAmount() <= 0
                ? ItemStack.EMPTY
                : view.getResource().toStack((int) Math.min(Integer.MAX_VALUE, view.getAmount()));
        }

        public ItemMatcher filter(int slot) {
            if (slot < 0 || slot >= slots.getSlotCount()) return ItemMatcher.none();
            // Fabric-native endpoints may expose their declared filter directly;
            // don't approximate this by a simulation of a full slot.
            if (slots instanceof FabricSlotFilterProvider filters) {
                ItemMatcher matcher = filters.filter(slot);
                if (matcher != null) return matcher;
            }
            // Legacy BCCE item handlers declare one representative filter stack
            // per slot. Keep that information when their Fabric Storage is used.
            if (owner instanceof IBCCapabilityProvider provider
                && provider.getCapability(CapUtil.CAP_ITEMS, side) instanceof IItemHandlerFiltered filtered) {
                ItemStack example = filtered.getFilter(slot);
                if (example != null && !example.isEmpty()) {
                    ItemStack expected = example.copyWithCount(1);
                    return candidate -> candidate != null && !candidate.isEmpty()
                        && ItemStack.matches(expected, candidate.copyWithCount(1));
                }
            }
            return ItemMatcher.any();
        }
    }

    private enum FluidsPort implements FluidTransfer {
        INSTANCE;
        public Optional<FluidPort> find(Level level, BlockPos pos, Direction side) {
            if (!valid(level, pos)) return Optional.empty();
            var state = level.getBlockState(pos);
            var blockEntity = level.getBlockEntity(pos);
            Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage =
                FluidStorage.SIDED.find(level, pos, state, blockEntity, side);
            return storage == null ? Optional.empty() : Optional.of(new NativeFluids(storage, level.registryAccess()));
        }
    }

    private record NativeFluids(Storage<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> storage,
                                HolderLookup.Provider registries)
        implements FluidPort {

        private static long drops(long milliBuckets) {
            if (milliBuckets <= 0) return 0L;
            return Math.min(milliBuckets, Long.MAX_VALUE / DROPLETS_PER_MB) * DROPLETS_PER_MB;
        }

        private static long aligned(long droplets) {
            return Math.max(0L, droplets) / DROPLETS_PER_MB * DROPLETS_PER_MB;
        }


        public FluidTransferResult insert(FluidVolume offered, OperationMode mode) {
            if (offered == null || offered.isEmpty()) {
                return FluidTransferResult.nothing(offered == null ? FluidAmount.ZERO : offered.amount());
            }
            var variant = FabricFluidVariants.fromApi(offered.requireVariant(), registries).orElse(null);
            if (variant == null) return FluidTransferResult.nothing(offered.amount());

            try (Transaction transaction = FabricTransactionScope.begin()) {
                long maxDrops = drops(offered.amount().milliBuckets());
                long available;
                // Probe in a nested transaction, abort it and request only whole millibuckets.
                try (Transaction probe = transaction.openNested()) {
                    available = aligned(storage.insert(variant, maxDrops, probe));
                }
                if (available <= 0) return FluidTransferResult.nothing(offered.amount());
                long accepted = storage.insert(variant, available, transaction);
                if (accepted != available) {
                    // No possible result with an unreported fractional remainder.
                    return FluidTransferResult.nothing(offered.amount());
                }
                if (mode == OperationMode.EXECUTE) transaction.commit();
                return FluidTransferResult.ofInsertion(offered, FluidAmount.of(accepted / DROPLETS_PER_MB));
            }
        }

        public FluidTransferResult extract(FluidMatcher matcher, FluidAmount maxAmount, OperationMode mode) {
            if (matcher == null || maxAmount == null || maxAmount.isZero()) {
                return FluidTransferResult.nothing(maxAmount == null ? FluidAmount.ZERO : maxAmount);
            }
            long limit = drops(maxAmount.milliBuckets());
            for (StorageView<net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant> view : storage) {
                if (view.isResourceBlank() || view.getAmount() < DROPLETS_PER_MB) continue;
                var nativeVariant = view.getResource();
                var variant = FabricFluidVariants.toApi(nativeVariant, registries).orElse(null);
                if (variant == null || !matcher.matches(variant, FLUID_MATCH_CONTEXT)) continue;

                try (Transaction transaction = FabricTransactionScope.begin()) {
                    long available;
                    try (Transaction probe = transaction.openNested()) {
                        available = aligned(view.extract(nativeVariant, limit, probe));
                    }
                    if (available <= 0) continue;
                    long amount = view.extract(nativeVariant, available, transaction);
                    if (amount != available) continue; // transaction rolls back
                    if (mode == OperationMode.EXECUTE) transaction.commit();
                    return FluidTransferResult.ofExtraction(maxAmount,
                        FluidVolume.of(variant, FluidAmount.of(amount / DROPLETS_PER_MB)));
                }
            }
            return FluidTransferResult.nothing(maxAmount);
        }
    }

    private enum Energy implements EnergyTransfer {
        INSTANCE;
        public Optional<ExternalEnergyPort> find(Level level, BlockPos pos, Direction side) {
            if (!valid(level, pos)) return Optional.empty();
            var state = level.getBlockState(pos);
            var blockEntity = level.getBlockEntity(pos);
            EnergyStorage energy = EnergyStorage.SIDED.find(level, pos, state, blockEntity, side);
            return energy == null ? Optional.empty() : Optional.of(new NativeEnergy(energy));
        }
    }

    private record NativeEnergy(EnergyStorage storage) implements ExternalEnergyPort {
        public long insert(long offered, OperationMode mode) {
            if (offered <= 0 || !storage.supportsInsertion()) return 0L;
            try (Transaction transaction = FabricTransactionScope.begin()) {
                long moved = Math.max(0L, Math.min(offered, storage.insert(offered, transaction)));
                if (mode == OperationMode.EXECUTE && moved > 0) transaction.commit();
                return moved;
            }
        }

        public long extract(long requested, OperationMode mode) {
            if (requested <= 0 || !storage.supportsExtraction()) return 0L;
            try (Transaction transaction = FabricTransactionScope.begin()) {
                long moved = Math.max(0L, Math.min(requested, storage.extract(requested, transaction)));
                if (mode == OperationMode.EXECUTE && moved > 0) transaction.commit();
                return moved;
            }
        }

        public long stored() { return storage.getAmount(); }
        public long capacity() { return storage.getCapacity(); }
        public boolean canInsert() { return storage.supportsInsertion(); }
        public boolean canExtract() { return storage.supportsExtraction(); }
    }
}
