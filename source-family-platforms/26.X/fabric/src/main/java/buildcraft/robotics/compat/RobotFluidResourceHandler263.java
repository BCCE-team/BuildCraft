/* Copyright (c) 2026 BCCE contributors. MPL-2.0. */
package buildcraft.robotics.compat;

import buildcraft.lib.compat.neoforge263.fluids.IFluidHandler;
import buildcraft.lib.compat.transfer.TransferJournal;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.lib.fluid.FluidCompatRegistry;
import buildcraft.lib.platform.storage.FabricOwnedStorageAdapters;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import java.util.Objects;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;

/** Fabric-native transactional wrapper for a robot's one 4000-mB tank. */
public final class RobotFluidResourceHandler263 {
    private RobotFluidResourceHandler263() {}

    public static Storage<FluidVariant> forRobot(EntityRobotBase robot) {
        Objects.requireNonNull(robot);
        return FabricOwnedStorageAdapters.fluids(new IFluidHandler() {
            public int getTanks() { return robot.getTanks(); }
            public FluidStack getFluidInTank(int tank) { return robot.getFluidInTank(tank); }
            public int getTankCapacity(int tank) { return robot.getTankCapacity(tank); }
            public boolean isFluidValid(int tank, FluidStack offered) { return robot.isFluidValid(tank, offered); }
            public int fill(FluidStack offered, IFluidHandler.FluidAction mode) {
                return robot.fill(offered, mode.simulate() ? buildcraft.transport.internal.pipe.FluidAction.SIMULATE : buildcraft.transport.internal.pipe.FluidAction.EXECUTE);
            }
            public FluidStack drain(FluidStack offered, IFluidHandler.FluidAction mode) {
                return robot.drain(offered, mode.simulate() ? buildcraft.transport.internal.pipe.FluidAction.SIMULATE : buildcraft.transport.internal.pipe.FluidAction.EXECUTE);
            }
            public FluidStack drain(int amount, IFluidHandler.FluidAction mode) {
                return robot.drain(amount, mode.simulate() ? buildcraft.transport.internal.pipe.FluidAction.SIMULATE : buildcraft.transport.internal.pipe.FluidAction.EXECUTE);
            }
        }, () -> {});
    }

    /** Disposable source for one-world-block rollback, entirely within the caller's transaction. */
    public static Storage<FluidVariant> forRollback(FluidStack original) {
        Objects.requireNonNull(original);
        final class RollbackCell implements IFluidHandler {
            private FluidStack stored = original.copy();
            private final int capacity = stored.getAmount();
            private final TransferJournal<FluidStack> journal = new TransferJournal<>(
                () -> stored.copy(), snapshot -> stored = snapshot.copy(), ignored -> {});
            public int getTanks() { return 1; }
            public FluidStack getFluidInTank(int tank) { return tank == 0 ? stored.copy() : FluidStack.EMPTY; }
            public int getTankCapacity(int tank) { return tank == 0 ? capacity : 0; }
            public boolean isFluidValid(int tank, FluidStack offered) {
                return tank == 0 && offered != null && !offered.isEmpty()
                    && (stored.isEmpty() || FluidCompatRegistry.areEquivalent(stored, offered));
            }
            public int fill(FluidStack offered, FluidAction action) {
                if (!isFluidValid(0, offered)) return 0;
                int accepted = Math.min(capacity - stored.getAmount(), offered.getAmount());
                if (accepted > 0 && action.execute()) {
                    journal.record();
                    if (stored.isEmpty()) stored = offered.copyWithAmount(accepted);
                    else stored.grow(accepted);
                }
                return accepted;
            }
            public FluidStack drain(FluidStack offered, FluidAction action) {
                if (offered == null || offered.isEmpty() || stored.isEmpty()
                    || !FluidCompatRegistry.areEquivalent(stored, offered)) return FluidStack.EMPTY;
                return drain(offered.getAmount(), action);
            }
            public FluidStack drain(int max, FluidAction action) {
                if (stored.isEmpty() || max <= 0) return FluidStack.EMPTY;
                int extracted = Math.min(max, stored.getAmount());
                FluidStack result = stored.copyWithAmount(extracted);
                if (action.execute()) {
                    journal.record();
                    stored.shrink(extracted);
                    if (stored.getAmount() <= 0) stored = FluidStack.EMPTY;
                }
                return result;
            }
        }
        return FabricOwnedStorageAdapters.fluids(new RollbackCell(), () -> {});
    }
}
