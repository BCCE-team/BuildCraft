//? source if >=26.3
package buildcraft.transport.compat;

import buildcraft.lib.compat.transfer.TransferJournal;
import buildcraft.lib.platform.storage.FilteredFluidStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import java.util.function.Predicate;

public final class NativeFluidStorage263 implements FilteredFluidStorage<FluidStack> {
    private final ResourceHandler<FluidResource> handler;

    public NativeFluidStorage263(ResourceHandler<FluidResource> handler) {
        this.handler = java.util.Objects.requireNonNull(handler);
    }

    public int getTanks() { return handler.size(); }
    public FluidStack getFluidInTank(int tank) {
        FluidResource resource = handler.getResource(tank);
        return resource.isEmpty() ? FluidStack.EMPTY : resource.toStack(handler.getAmountAsInt(tank));
    }
    public int getTankCapacity(int tank) { return handler.getCapacityAsInt(tank, handler.getResource(tank)); }
    public boolean isFluidValid(int tank, FluidStack stack) {
        return !stack.isEmpty() && handler.isValid(tank, FluidResource.of(stack));
    }
    public int fill(FluidStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty() || stack.getAmount() <= 0) return 0;
        try (Transaction tx = Transaction.open(TransferJournal.current())) {
            int amount = handler.insert(FluidResource.of(stack), stack.getAmount(), tx);
            if (!simulate && amount > 0) tx.commit();
            return Math.max(0, Math.min(stack.getAmount(), amount));
        }
    }
    public FluidStack drain(FluidStack requested, boolean simulate) {
        if (requested == null || requested.isEmpty() || requested.getAmount() <= 0) return FluidStack.EMPTY;
        return drainMatching(stack -> FluidResource.of(stack).equals(FluidResource.of(requested)), requested.getAmount(), simulate);
    }
    public FluidStack drain(int amount, boolean simulate) {
        return drainMatching(stack -> !stack.isEmpty(), amount, simulate);
    }
    public FluidStack drain(Predicate<FluidStack> filter, int amount, boolean simulate) {
        return drainMatching(filter, amount, simulate);
    }
    private FluidStack drainMatching(Predicate<FluidStack> filter, int amount, boolean simulate) {
        if (amount <= 0) return FluidStack.EMPTY;
        for (int i = 0; i < handler.size(); ++i) {
            FluidResource resource = handler.getResource(i);
            if (resource.isEmpty()) continue;
            FluidStack stack = resource.toStack(Math.max(1, handler.getAmountAsInt(i)));
            if (!filter.test(stack)) continue;
            int offered = Math.min(amount, handler.getAmountAsInt(i));
            if (offered <= 0) continue;
            try (Transaction tx = Transaction.open(TransferJournal.current())) {
                int extracted = handler.extract(i, resource, offered, tx);
                if (extracted > 0) {
                    if (!simulate) tx.commit();
                    return resource.toStack(Math.min(offered, extracted));
                }
            }
        }
        return FluidStack.EMPTY;
    }
}
