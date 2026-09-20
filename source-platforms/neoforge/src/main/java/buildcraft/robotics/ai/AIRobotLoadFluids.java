package buildcraft.robotics.ai;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.inventory.filter.ArrayFluidFilter;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.statements.ActionStationProvideFluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

public class AIRobotLoadFluids extends AIRobot {
    private static final int FLUID_LOAD_DELAY_TICKS = 10;
    private IFluidFilter filter;
    private int waitedCycles;

    public AIRobotLoadFluids(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotLoadFluids(EntityRobotBase robot, IFluidFilter filter) {
        this(robot);
        this.filter = filter;
        setSuccess(false);
    }

    @Override
    public void update() {
        if (filter == null) {
            setSuccess(false);
            terminate();
            return;
        }

        waitedCycles++;
        if (waitedCycles > FLUID_LOAD_DELAY_TICKS) {
            int loaded = load(robot, robot.getDockingStation(), filter, true);
            if (loaded <= 0) {
                terminate();
            } else {
                setSuccess(true);
                waitedCycles = 0;
            }
        }
    }

    public static int load(EntityRobotBase robot, DockingStation station, IFluidFilter filter, boolean doLoad) {
        if (robot == null || station == null || filter == null) {
            return 0;
        }

        FluidStorage<FluidStack> handler = station.getFluidInput();
        if (handler == null) {
            return 0;
        }

        FluidStack drainable = handler.drain(FluidType.BUCKET_VOLUME, true);
        if (drainable.isEmpty() || !filter.matches(drainable)) {
            return 0;
        }
        if (!ActionRobotFilter.canInteractWithFluid(station, new ArrayFluidFilter(drainable), ActionStationProvideFluids.class)) {
            return 0;
        }

        int fillable = robot.fill(drainable, FluidAction.SIMULATE);
        if (fillable <= 0) {
            return 0;
        }

        FluidStack toDrain = drainable.copy();
        toDrain.setAmount(Math.min(toDrain.getAmount(), fillable));
        if (!doLoad) {
            FluidStack simulatedDrain = handler.drain(toDrain, true);
            return simulatedDrain.isEmpty() ? 0 : Math.min(simulatedDrain.getAmount(), fillable);
        }

        // Execute source-first, then return any unexpectedly rejected remainder to the source. This keeps transfer
        // conservative even when a mutable destination changes after simulation.
        FluidStack drained = handler.drain(toDrain, false);
        if (drained.isEmpty()) {
            return 0;
        }
        if (!filter.matches(drained)) {
            int returned = handler.fill(drained, false);
            if (returned < drained.getAmount()) {
                BCLog.logger.error("Robot source returned an unexpected fluid and accepted only " + returned
                    + " of " + drained.getAmount() + " mB during rollback");
            }
            return 0;
        }

        int filled = robot.fill(drained, FluidAction.EXECUTE);
        if (filled < drained.getAmount()) {
            FluidStack remainder = drained.copy();
            remainder.setAmount(drained.getAmount() - filled);
            int returned = handler.fill(remainder, false);
            if (returned < remainder.getAmount()) {
                BCLog.logger.error("Robot fluid load rollback was only partially accepted: returned " + returned
                    + " of " + remainder.getAmount() + " mB");
            }
        }
        return filled;
    }

    @Override
    public int getEnergyCost() {
        return 8;
    }
}
