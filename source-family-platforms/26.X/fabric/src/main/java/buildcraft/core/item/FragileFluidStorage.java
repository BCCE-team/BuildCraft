/*
 * Copyright (c) 2026 the BuildCraft Community Edition contributors.
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core.item;

import buildcraft.lib.fluid.FluidStack;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Fabric drain-only equivalent of Core's NeoForge FragileFluidResourceHandler.
 *
 * <p>Shard fluid is measured in mB; Fabric's public transfer unit is droplets.
 * Partial drains rewrite the exact item (including Data Components) through the
 * transactional ContainerItemContext. Full drains consume the shard. There is
 * deliberately no empty-container remainder and no fluid insertion.</p>
 */
public final class FragileFluidStorage implements Storage<FluidVariant> {
    private static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L;
    private final ContainerItemContext context;

    public FragileFluidStorage(ContainerItemContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    private ItemStack shard() {
        if (context.getAmount() < 1) return ItemStack.EMPTY;
        ItemStack item = context.getItemVariant().toStack();
        return item.getItem() instanceof ItemFragileFluidContainer ? item : ItemStack.EMPTY;
    }

    private FluidStack contents() {
        return ItemFragileFluidContainer.getFluid(shard());
    }

    private FluidVariant variant() {
        FluidStack fluid = contents();
        return fluid.isEmpty() ? FluidVariant.blank()
            : FluidVariant.of(fluid.getFluid(), fluid.getComponentsPatch());
    }

    public boolean supportsInsertion() { return false; }
    public boolean supportsExtraction() { return true; }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
        return 0;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
        if (resource == null || resource.isBlank() || maxAmount < DROPLETS_PER_MB) return 0;
        ItemStack original = shard();
        FluidStack held = ItemFragileFluidContainer.getFluid(original);
        if (original.isEmpty() || held.isEmpty() || !resource.equals(variant())) return 0;
        long drainedMb = Math.min(held.getAmount(), maxAmount / DROPLETS_PER_MB);
        if (drainedMb <= 0) return 0;

        // Nested transaction: any failed item exchange/consumption must not
        // return fluid nor commit an item mutation to the caller's transaction.
        try (Transaction nested = Transaction.openNested(transaction)) {
            long transformed;
            if (drainedMb == held.getAmount()) {
                transformed = context.extract(ItemVariant.of(original), 1, nested);
            } else {
                ItemStack remainder = original.copyWithCount(1);
                ItemFragileFluidContainer.setFluid(remainder,
                    held.copyWithAmount(held.getAmount() - (int) drainedMb));
                transformed = context.exchange(ItemVariant.of(remainder), 1, nested);
            }
            if (transformed != 1) return 0;
            nested.commit();
            return drainedMb * DROPLETS_PER_MB;
        }
    }

    @Override
    public Iterator<StorageView<FluidVariant>> iterator() {
        return List.<StorageView<FluidVariant>>of(new StorageView<>() {
            public boolean isResourceBlank() { return variant().isBlank(); }
            public FluidVariant getResource() { return variant(); }
            public long getAmount() { return contents().getAmount() * DROPLETS_PER_MB; }
            public long getCapacity() {
                return Math.max(ItemFragileFluidContainer.MAX_FLUID_HELD, contents().getAmount())
                    * DROPLETS_PER_MB;
            }
            public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
                return FragileFluidStorage.this.extract(resource, maxAmount, transaction);
            }
        }).iterator();
    }
}
