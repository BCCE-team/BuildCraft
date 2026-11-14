/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.lib.compat.neoforge263.fluids;

import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.compat.transfer.TransferJournal;
import buildcraft.lib.internal.transfer.FabricTransactionScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.base.SingleStackStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric Transfer replacement for BCCE's 26.3 fluid-item operations. */
public final class FluidUtil {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;
    private FluidUtil() {}

    private static FluidVariant variant(FluidStack stack) {
        return stack == null || stack.isEmpty() ? FluidVariant.blank()
            : FluidVariant.of(stack.getFluid(), stack.getComponentsPatch());
    }
    private static FluidStack fluid(FluidVariant variant, long droplets) {
        if (variant == null || variant.isBlank() || droplets < DROPLETS_PER_MB) return FluidStack.EMPTY;
        return new FluidStack(variant.getFluid(), bounded(droplets / DROPLETS_PER_MB), variant.getComponentsPatch());
    }
    private static int bounded(long number) { return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, number)); }

    private static final class MutableItem extends SingleStackStorage {
        private ItemStack current;
        MutableItem(ItemStack stack) { current = stack.copyWithCount(1); }
        @Override protected ItemStack getStack() { return current; }
        @Override protected void setStack(ItemStack stack) { current = stack; }
        ItemStack container() { return current.copy(); }
    }

    private static List<StorageView<FluidVariant>> views(Storage<FluidVariant> storage) {
        List<StorageView<FluidVariant>> result = new ArrayList<>();
        for (StorageView<FluidVariant> view : storage) result.add(view);
        return result;
    }

    public static Optional<IFluidHandlerItem> getFluidHandler(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        MutableItem item = new MutableItem(stack);
        Storage<FluidVariant> storage = FluidStorage.ITEM.find(item.container(), ContainerItemContext.ofSingleSlot(item));
        if (storage == null) return Optional.empty();
        return Optional.of(new IFluidHandlerItem() {
            @Override public ItemStack getContainer() { return item.container(); }
            @Override public int getTanks() { return views(storage).size(); }
            @Override public FluidStack getFluidInTank(int index) {
                StorageView<FluidVariant> view = views(storage).get(index);
                return view.isResourceBlank() ? FluidStack.EMPTY : fluid(view.getResource(), view.getAmount());
            }
            @Override public int getTankCapacity(int index) {
                return bounded(views(storage).get(index).getCapacity() / DROPLETS_PER_MB);
            }
            @Override public boolean isFluidValid(int index, FluidStack stack) {
                if (stack == null || stack.isEmpty()) return false;
                try (Transaction tx = FabricTransactionScope.begin()) {
                    StorageView<FluidVariant> view = views(storage).get(index);
                    if (!view.isResourceBlank() && !view.getResource().equals(variant(stack))) return false;
                    if (view.getAmount() + DROPLETS_PER_MB > view.getCapacity()) return false;
                    return storage.insert(variant(stack), DROPLETS_PER_MB, tx) >= DROPLETS_PER_MB;
                }
            }
            @Override public int fill(FluidStack offered, FluidAction action) {
                if (offered == null || offered.isEmpty()) return 0;
                try (Transaction tx = FabricTransactionScope.begin()) {
                    long moved = storage.insert(variant(offered), (long) offered.getAmount() * DROPLETS_PER_MB, tx);
                    if (moved % DROPLETS_PER_MB != 0) return 0; // Abort a partial mB.
                    if (action.execute()) tx.commit();
                    return bounded(moved / DROPLETS_PER_MB);
                }
            }
            @Override public FluidStack drain(FluidStack offered, FluidAction action) {
                if (offered == null || offered.isEmpty()) return FluidStack.EMPTY;
                try (Transaction tx = FabricTransactionScope.begin()) {
                    long moved = storage.extract(variant(offered), (long) offered.getAmount() * DROPLETS_PER_MB, tx);
                    if (moved % DROPLETS_PER_MB != 0) return FluidStack.EMPTY;
                    if (action.execute()) tx.commit();
                    return offered.copyWithAmount(bounded(moved / DROPLETS_PER_MB));
                }
            }
            @Override public FluidStack drain(int maxDrain, FluidAction action) {
                if (maxDrain <= 0) return FluidStack.EMPTY;
                for (StorageView<FluidVariant> view : views(storage)) {
                    if (view.isResourceBlank() || view.getAmount() < DROPLETS_PER_MB) continue;
                    FluidVariant resource = view.getResource();
                    try (Transaction tx = FabricTransactionScope.begin()) {
                        long moved = storage.extract(resource, (long) maxDrain * DROPLETS_PER_MB, tx);
                        if (moved % DROPLETS_PER_MB != 0) return FluidStack.EMPTY;
                        if (action.execute()) tx.commit();
                        return fluid(resource, moved);
                    }
                }
                return FluidStack.EMPTY;
            }
        });
    }

    public static Optional<FluidStack> getFluidContained(ItemStack stack) {
        return getFluidHandler(stack).map(handler -> {
            for (int i=0; i<handler.getTanks(); i++) {
                FluidStack fluid = handler.getFluidInTank(i);
                if (!fluid.isEmpty()) return fluid;
            }
            return FluidStack.EMPTY;
        }).filter(stackValue -> !stackValue.isEmpty());
    }

    public static FluidStack getFirstStackContained(ItemStack stack) {
        return getFluidContained(stack).orElse(FluidStack.EMPTY);
    }

    public static ItemStack getFilledBucket(FluidStack fluid) {
        if (fluid == null || fluid.isEmpty() || fluid.getAmount() < 1000
            || !fluid.getComponentsPatch().isEmpty()) return ItemStack.EMPTY;
        var bucket = fluid.getFluid().getBucket();
        return bucket == null || bucket == net.minecraft.world.item.Items.AIR
            ? ItemStack.EMPTY : bucket.getDefaultInstance();
    }

    /** Transactions include the player hand and BCCE-owned, journaled tank. */
    public static boolean interactWithFluidHandler(Player player, InteractionHand hand, IFluidHandler tank) {
        if (player == null || tank == null || player.getItemInHand(hand).isEmpty()) return false;
        if (player.level().isClientSide()) return true;
        ItemStack held = player.getItemInHand(hand);
        Storage<FluidVariant> item = FluidStorage.ITEM.find(held, ContainerItemContext.forPlayerInteraction(player, hand));
        if (item == null) return transferUnregisteredBucketToTank(player, hand, held, tank);
        try (Transaction tx = FabricTransactionScope.begin()) {
            for (StorageView<FluidVariant> view : item) {
                if (view.isResourceBlank() || view.getAmount() < DROPLETS_PER_MB) continue;
                FluidVariant resource = view.getResource();
                int offered = bounded(view.getAmount() / DROPLETS_PER_MB);
                int accepted = tank.fill(fluid(resource, offered), IFluidHandler.FluidAction.SIMULATE);
                if (accepted <= 0) continue;
                long extracted = item.extract(resource, (long) accepted * DROPLETS_PER_MB, tx);
                if (extracted <= 0 || extracted % DROPLETS_PER_MB != 0) continue;
                int actuallyAccepted = tank.fill(fluid(resource, extracted), IFluidHandler.FluidAction.EXECUTE);
                if ((long) actuallyAccepted * DROPLETS_PER_MB != extracted)
                    throw new IllegalStateException("Item to tank fluid amount mismatch");
                tx.commit();
                return true;
            }
            for (int i=0; i<tank.getTanks(); i++) {
                FluidStack available = tank.getFluidInTank(i);
                if (available == null || available.isEmpty()) continue;
                FluidStack simulated = tank.drain(available, IFluidHandler.FluidAction.SIMULATE);
                if (simulated.isEmpty()) continue;
                long moved = item.insert(variant(available), (long) simulated.getAmount() * DROPLETS_PER_MB, tx);
                if (moved <= 0 || moved % DROPLETS_PER_MB != 0) continue;
                FluidStack extracted = tank.drain(available.copyWithAmount(bounded(moved / DROPLETS_PER_MB)),
                    IFluidHandler.FluidAction.EXECUTE);
                if ((long) extracted.getAmount() * DROPLETS_PER_MB != moved)
                    throw new IllegalStateException("Tank to item fluid amount mismatch");
                tx.commit();
                return true;
            }
        }
        // Some modded BucketItems expose a Fabric item storage with no usable
        // extract view. The lookup succeeded, but no transfer was possible.
        // Reuse the same strict 1000 mB fallback as the null-storage case.
        return transferUnregisteredBucketToTank(player, hand, held, tank);
    }

    /**
     * Fabric's generic item-fluid lookup may not be installed for custom BuildCraft
     * {@link BucketItem}s. A whole bucket can still be inserted safely into a tank.
     * Do not handle partial buckets or overwrite a nonempty stack without a check.
     * The native Fabric Storage implementation remains authoritative when present.
     */
    private static boolean transferUnregisteredBucketToTank(Player player, InteractionHand hand,
                                                              ItemStack held, IFluidHandler tank) {
        if (!(held.getItem() instanceof BucketItem bucket) || bucket.getContent() == Fluids.EMPTY) {
            return false;
        }
        FluidStack volume = new FluidStack(bucket.getContent(), 1000);
        if (tank.fill(volume, IFluidHandler.FluidAction.SIMULATE) != 1000) {
            return false;
        }
        if (!player.isCreative()) {
            // A bucket is consumed only if the entire 1000 mB was accepted.
            if (held.getCount() != 1) return false;
        }
        int accepted = tank.fill(volume, IFluidHandler.FluidAction.EXECUTE);
        if (accepted != 1000) {
            throw new IllegalStateException("Tank accepted " + accepted + " mB from a 1000 mB bucket");
        }
        if (!player.isCreative()) {
            player.setItemInHand(hand, new ItemStack(Items.BUCKET));
        }
        return true;
    }

    /** Native Fabric-to-Fabric fluid transfer, with no unjournaled legacy mutations. */
    public static boolean interactWithFluidHandler(Player player, InteractionHand hand, BlockPos ignored,
        Storage<FluidVariant> storage, Object side) {
        if (player == null || storage == null || player.getItemInHand(hand).isEmpty()) return false;
        if (player.level().isClientSide()) return true;
        Storage<FluidVariant> held = FluidStorage.ITEM.find(player.getItemInHand(hand),
            ContainerItemContext.forPlayerInteraction(player, hand));
        if (held == null) return false;
        try (Transaction tx = FabricTransactionScope.begin()) {
            if (moveWholeMillibuckets(held, storage, tx) || moveWholeMillibuckets(storage, held, tx)) {
                tx.commit();
                return true;
            }
            return false;
        }
    }

    private static boolean moveWholeMillibuckets(Storage<FluidVariant> from,
        Storage<FluidVariant> to, Transaction parent) {
        for (StorageView<FluidVariant> view : from) {
            if (view.isResourceBlank()) continue;
            FluidVariant resource = view.getResource();
            long whole = view.getAmount() / DROPLETS_PER_MB * DROPLETS_PER_MB;
            if (whole < DROPLETS_PER_MB) continue;
            try (Transaction nested = Transaction.openNested(parent)) {
                long inserted = to.insert(resource, whole, nested);
                // Never mutate either side by a fractional mB.
                if (inserted < DROPLETS_PER_MB || inserted % DROPLETS_PER_MB != 0) continue;
                long extracted = from.extract(resource, inserted, nested);
                if (extracted != inserted) continue;
                nested.commit();
                return true;
            }
        }
        return false;
    }

    /** Place a single source bucket only after reserving exactly 1000 mB. */
    public static FluidStack tryPlaceFluid(Storage<FluidVariant> source, Player player,
        Level level, BlockPos pos, boolean place, Object side) {
        if (source == null || level == null || pos == null) return FluidStack.EMPTY;
        BlockState state = level.getBlockState(pos);
        boolean container = state.getBlock() instanceof LiquidBlockContainer;
        if (!container && !state.canBeReplaced()) return FluidStack.EMPTY;
        for (StorageView<FluidVariant> view : source) {
            if (view.isResourceBlank() || view.getAmount() < FluidConstants.BUCKET) continue;
            FluidVariant variant = view.getResource();
            if (!variant.getComponentsPatch().isEmpty()) continue; // World blocks cannot retain a variant patch.
            var fluidState = variant.getFluid().defaultFluidState();
            if (fluidState.isEmpty()) continue;
            FluidStack bucket = fluid(variant, FluidConstants.BUCKET);
            if (!place) return bucket;
            if (level.isClientSide()) return FluidStack.EMPTY;
            try (Transaction tx = FabricTransactionScope.begin()) {
                if (source.extract(variant, FluidConstants.BUCKET, tx) != FluidConstants.BUCKET) continue;
                boolean placed = container
                    ? ((LiquidBlockContainer)state.getBlock()).placeLiquid(level, pos, state, fluidState)
                    : level.setBlock(pos, fluidState.createLegacyBlock(), 3);
                if (!placed) continue;
                tx.commit();
                return bucket;
            }
        }
        return FluidStack.EMPTY;
    }
}
