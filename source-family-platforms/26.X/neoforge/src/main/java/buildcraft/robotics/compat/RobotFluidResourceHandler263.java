//? source if >=26.3
package buildcraft.robotics.compat;

import buildcraft.lib.compat.transfer.TransferJournal;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.transport.internal.pipe.FluidAction;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/** A single robot fluid cell or one disposable, transactional world-placement rollback cell. */
public final class RobotFluidResourceHandler263 implements ResourceHandler<FluidResource> {
    private final EntityRobotBase robot;
    private FluidStack rollbackContents = FluidStack.EMPTY;
    private final int rollbackCapacity;
    private final TransferJournal<FluidStack> rollbackJournal = new TransferJournal<>(
        () -> rollbackContents.copy(),
        previous -> rollbackContents = previous.copy(),
        ignored -> {}
    );

    public RobotFluidResourceHandler263(EntityRobotBase robot) {
        this.robot = java.util.Objects.requireNonNull(robot);
        rollbackCapacity = 0;
    }

    /** The source block has already been drained; restore its entire fluid value or none. */
    public static RobotFluidResourceHandler263 forRollback(FluidStack stored) {
        return new RobotFluidResourceHandler263(stored);
    }

    private RobotFluidResourceHandler263(FluidStack stored) {
        robot = null;
        rollbackContents = java.util.Objects.requireNonNull(stored).copy();
        rollbackCapacity = rollbackContents.getAmount();
    }

    private void checkIndex(int index) { java.util.Objects.checkIndex(index, 1); }

    public int size() { return 1; }

    public FluidResource getResource(int index) {
        checkIndex(index);
        return FluidResource.of(current());
    }

    public long getAmountAsLong(int index) {
        checkIndex(index);
        return current().getAmount();
    }

    public long getCapacityAsLong(int index, FluidResource resource) {
        checkIndex(index);
        return resource.isEmpty() || isValid(index, resource)
            ? robot == null ? rollbackCapacity : robot.getTankCapacity(0) : 0;
    }

    public boolean isValid(int index, FluidResource resource) {
        checkIndex(index);
        if (resource.isEmpty()) return false;
        if (robot != null) return robot.isFluidValid(0, resource.toStack(1));
        return resource.equals(FluidResource.of(rollbackContents));
    }

    private FluidStack current() {
        return robot == null ? rollbackContents.copy() : robot.getFluidInTank(0);
    }

    public int insert(int index, FluidResource resource, int amount, TransactionContext context) {
        checkIndex(index);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0 || robot == null || !isValid(index, resource)) return 0;
        try (Transaction tx = Transaction.open(context)) {
            int inserted = robot.fill(resource.toStack(amount), FluidAction.EXECUTE);
            if (inserted > 0) tx.commit();
            return inserted;
        }
    }

    public int extract(int index, FluidResource resource, int amount, TransactionContext context) {
        checkIndex(index);
        TransferPreconditions.checkNonEmptyNonNegative(resource, amount);
        if (amount == 0 || !resource.equals(getResource(0))) return 0;
        try (Transaction tx = Transaction.open(context)) {
            int extracted;
            if (robot != null) {
                extracted = robot.drain(resource.toStack(amount), FluidAction.EXECUTE).getAmount();
            } else {
                rollbackJournal.record();
                extracted = Math.min(amount, rollbackContents.getAmount());
                int remaining = rollbackContents.getAmount() - extracted;
                rollbackContents = remaining <= 0 ? FluidStack.EMPTY : rollbackContents.copyWithAmount(remaining);
            }
            if (extracted > 0) tx.commit();
            return extracted;
        }
    }
}
