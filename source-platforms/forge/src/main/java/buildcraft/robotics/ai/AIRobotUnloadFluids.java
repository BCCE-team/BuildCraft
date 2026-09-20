package buildcraft.robotics.ai;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.internal.statement.StatementSlot;
import buildcraft.lib.inventory.filter.SimpleFluidFilter;
import buildcraft.lib.platform.storage.FluidStorage;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.statements.ActionStationAcceptFluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

public class AIRobotUnloadFluids extends AIRobot {
    private static final int FLUID_UNLOAD_INITIAL_DELAY_TICKS = 10;
    private int waitedCycles;
    private boolean requireAcceptAction;

    public AIRobotUnloadFluids(EntityRobotBase robot) {
        super(robot);
        setSuccess(false);
    }

    public AIRobotUnloadFluids(EntityRobotBase robot, boolean requireAcceptAction) {
        this(robot);
        this.requireAcceptAction = requireAcceptAction;
    }

    @Override
    public void update() {
        waitedCycles++;
        if (waitedCycles <= FLUID_UNLOAD_INITIAL_DELAY_TICKS) {
            return;
        }

        int moved = unload(robot, robot.getDockingStation(), true, requireAcceptAction);
        if (moved > 0) {
            // Keep trying every tick after the initial 40 tick docking delay.
            // The original BuildCraft 7.1.x robot does not wait 40 ticks per bucket;
            // it waits once, then pushes fluid as fast as the target pipe can accept it.
            setSuccess(true);
        } else {
            setSuccess(robot.getFluidInTank(0).isEmpty());
            terminate();
        }
    }

    public static int unload(EntityRobotBase robot, DockingStation station, boolean doUnload) {
        return unload(robot, station, doUnload, false);
    }

    public static int unload(EntityRobotBase robot, DockingStation station, boolean doUnload, boolean requireAcceptAction) {
        if (robot == null || station == null) {
            return 0;
        }

        FluidStack fluidInRobot = robot.getFluidInTank(0);
        if (fluidInRobot.isEmpty() || !canUnloadFluid(station, new SimpleFluidFilter(fluidInRobot), requireAcceptAction)) {
            return 0;
        }

        FluidStorage<FluidStack> fluidHandler = station.getFluidOutput();
        if (fluidHandler == null) {
            return 0;
        }

        FluidStack drainable = robot.drain(FluidType.BUCKET_VOLUME, FluidAction.SIMULATE);
        if (drainable.isEmpty()) {
            return 0;
        }

        int fillable = fluidHandler.fill(drainable.copy(), true);
        if (fillable <= 0) {
            return 0;
        }

        FluidStack toDrain = drainable.copy();
        toDrain.setAmount(Math.min(toDrain.getAmount(), fillable));
        if (!doUnload) {
            return toDrain.getAmount();
        }

        // Drain the robot first. If the target accepts less than promised, put the remainder back into the robot.
        FluidStack drained = robot.drain(toDrain, FluidAction.EXECUTE);
        if (drained.isEmpty()) {
            return 0;
        }
        int filled = fluidHandler.fill(drained, false);
        if (filled < drained.getAmount()) {
            FluidStack remainder = drained.copy();
            remainder.setAmount(drained.getAmount() - filled);
            int returned = robot.fill(remainder, FluidAction.EXECUTE);
            if (returned < remainder.getAmount()) {
                BCLog.logger.error("Robot fluid unload rollback was only partially accepted: returned " + returned
                    + " of " + remainder.getAmount() + " mB");
            }
        }
        return filled;
    }

    private static boolean canUnloadFluid(DockingStation station, IFluidFilter filter, boolean requireAcceptAction) {
        boolean hasExplicitAcceptAction = false;
        for (StatementSlot slot : station.getActiveActions()) {
            if (slot.statement instanceof ActionStationAcceptFluids) {
                hasExplicitAcceptAction = true;
                break;
            }
        }

        if (hasExplicitAcceptAction) {
            return ActionRobotFilter.canInteractWithFluid(station, filter, ActionStationAcceptFluids.class);
        }

        if (requireAcceptAction) {
            return false;
        }

        // A plain robot station on a fluid pipe should be usable without forcing the player to add a gate action.
        return station.getFluidOutput() != null;
    }

    @Override
    public int getEnergyCost() {
        return 10;
    }
}
