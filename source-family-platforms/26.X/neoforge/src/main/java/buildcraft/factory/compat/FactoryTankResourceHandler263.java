//? source if >=26.3
package buildcraft.factory.compat;

import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.fluid.Tank;
import java.util.List;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** ResourceHandler backed by the original BuildCraft tanks and their transaction journals. */
public final class FactoryTankResourceHandler263 implements ResourceHandler<FluidResource> {
    private final List<Tank> tanks;

    public FactoryTankResourceHandler263(Tank... tanks) {
        this.tanks = List.of(tanks);
    }

    @Override
    public int size() { return tanks.size(); }

    private Tank at(int index) { return tanks.get(index); }

    @Override
    public FluidResource getResource(int index) {
        return FluidResource.of(at(index).getFluid());
    }

    @Override
    public long getAmountAsLong(int index) {
        return at(index).getFluidAmount();
    }

    @Override
    public long getCapacityAsLong(int index, FluidResource resource) {
        Tank tank = at(index);
        return resource.isEmpty() || isValid(index, resource) ? tank.getCapacity() : 0;
    }

    @Override
    public boolean isValid(int index, FluidResource resource) {
        return !resource.isEmpty() && at(index).isFluidValid(resource.toStack(1));
    }

    @Override
    public int insert(int index, FluidResource resource, int amount, TransactionContext context) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Tank tank = at(index);
        if (amount == 0 || !tank.canFill() || !isValid(index, resource)) return 0;
        FluidStack offered = FluidCompatRegistry.canonicalize(resource.toStack(amount));
        try (Transaction tx = Transaction.open(context)) {
            FluidStack stored = tank.getFluid();
            if (!stored.isEmpty() && !FluidCompatRegistry.areEquivalent(stored, offered)) return 0;
            int inserted = Math.min(amount, Math.max(0, tank.getCapacity() - tank.getFluidAmount()));
            if (inserted > 0) {
                tank.setFluid(stored.isEmpty() ? offered.copyWithAmount(inserted)
                    : stored.copyWithAmount(stored.getAmount() + inserted));
                tx.commit();
            }
            return inserted;
        }
    }

    @Override
    public int extract(int index, FluidResource resource, int amount, TransactionContext context) {
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        Tank tank = at(index);
        if (amount == 0 || resource.isEmpty() || !tank.canDrain()) return 0;
        try (Transaction tx = Transaction.open(context)) {
            FluidStack stored = tank.getFluid();
            if (stored.isEmpty() || !FluidCompatRegistry.areEquivalent(stored, resource.toStack(1))) return 0;
            int extracted = Math.min(amount, stored.getAmount());
            if (extracted > 0) {
                int remaining = stored.getAmount() - extracted;
                tank.setFluid(remaining == 0 ? FluidStack.EMPTY : stored.copyWithAmount(remaining));
                tx.commit();
            }
            return extracted;
        }
    }
}