//? source if >=1.21.11
/*
 * Copyright (c) 2026 the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package buildcraft.core.item;

import java.util.Objects;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** A drain-only shard. Emptying consumes it; cancellation restores the actual item location. */
public final class FragileFluidResourceHandler implements ResourceHandler<FluidResource> {
    private final ItemAccess access;

    public FragileFluidResourceHandler(ItemAccess access) { this.access = Objects.requireNonNull(access); }

    private ItemStack container() {
        ItemStack stack = access.getResource().toStack();
        return access.getAmount() > 0 && stack.getItem() instanceof ItemFragileFluidContainer
            ? stack : ItemStack.EMPTY;
    }
    private FluidStack contents() { return ItemFragileFluidContainer.getFluid(container()); }
    private static void checkIndex(int index) { Objects.checkIndex(index, 1); }

    public int size() { return 1; }
    public FluidResource getResource(int index) { checkIndex(index); return FluidResource.of(contents()); }
    public long getAmountAsLong(int index) { checkIndex(index); return contents().getAmount(); }
    public long getCapacityAsLong(int index, FluidResource resource) {
        checkIndex(index);
        return ItemFragileFluidContainer.MAX_FLUID_HELD;
    }
    public boolean isValid(int index, FluidResource resource) { checkIndex(index); return false; }
    public int insert(int index, FluidResource resource, int amount, TransactionContext transaction) {
        checkIndex(index);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        return 0;
    }
    public int extract(int index, FluidResource resource, int amount, TransactionContext transaction) {
        checkIndex(index);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        ItemStack original = container();
        FluidStack fluid = ItemFragileFluidContainer.getFluid(original);
        if (original.isEmpty() || fluid.isEmpty() || amount == 0 || !resource.equals(FluidResource.of(fluid))) return 0;
        int drained = Math.min(amount, fluid.getAmount());
        try (Transaction operation = Transaction.open(transaction)) {
            int changed;
            if (drained == fluid.getAmount()) {
                // No reusable empty shard: preserve the legacy consume-on-empty contract.
                changed = access.extract(ItemResource.of(original), 1, operation);
            } else {
                ItemStack remainder = original.copyWithCount(1);
                ItemFragileFluidContainer.setFluid(remainder, fluid.copyWithAmount(fluid.getAmount() - drained));
                changed = access.exchange(ItemResource.of(remainder), 1, operation);
            }
            if (changed != 1) return 0;
            operation.commit();
            return drained;
        }
    }
}
