package buildcraft.robotics.ai;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.robotics.statements.ActionRobotFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;

/** Pumps one source fluid block into the robot's internal tank, matching the classic BuildCraft pump robot cadence. */
public class AIRobotPumpBlock extends AIRobot {
    private BlockIndex blockToPump;
    private long waited;
    private int pumped;

    public AIRobotPumpBlock(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotPumpBlock(EntityRobotBase robot, BlockIndex blockToPump) {
        this(robot);
        this.blockToPump = blockToPump;
    }

    @Override
    public void start() {
        if (blockToPump == null) {
            setSuccess(false);
            terminate();
            return;
        }
        robot.aimItemAt(blockToPump.toBlockPos());
        robot.setItemActive(true);
    }

    @Override
    public void update() {
        if (blockToPump == null) {
            setSuccess(false);
            terminate();
            return;
        }

        if (waited < 40) {
            waited++;
            return;
        }

        BlockPos pos = blockToPump.toBlockPos();
        //? if <1.20 {
        if (!robot.level.isLoaded(pos)) {
        //?} else {
        /*?
        if (!robot.level().isLoaded(pos)) {
        ?*/
        //?}
            setSuccess(false);
            terminate();
            return;
        }

        if (!RobotAutomationSupport.permitsBlock(
            robot, pos, WorldOperationKind.FLUID_DRAIN, OperationMode.EXECUTE
        )) {
            setSuccess(false);
            terminate();
            return;
        }

        //? if <1.20 {
        FluidStack simulated = BlockUtil.drainBlock(robot.level, pos, false);
        //?} else {
        /*?
        FluidStack simulated = BlockUtil.drainBlock(robot.level(), pos, false);
        ?*/
        //?}
        // The fluid and Gate rules may have changed during the 40-tick flight/use delay.
        // Never drain a source that no longer satisfies the current filter.
        IFluidFilter activeFilter = ActionRobotFilter.getGateFluidFilter(robot.getLinkedStation());
        if (!simulated.isEmpty() && activeFilter != null && !activeFilter.matches(simulated)) {
            setSuccess(false);
            terminate();
            return;
        }
        if (!simulated.isEmpty()) {
            int accepted = robot.fill(simulated, FluidAction.SIMULATE);
            // A source block is indivisible here. If the robot cannot hold the complete simulated drain, leave the
            // world block untouched and let the board choose another target or unload its current contents.
            if (accepted >= simulated.getAmount()) {
                //? if <1.20 {
                FluidStack drained = BlockUtil.drainBlock(robot.level, pos, true);
                //?} else {
                /*?
                FluidStack drained = BlockUtil.drainBlock(robot.level(), pos, true);
                ?*/
                //?}
                if (!drained.isEmpty()) {
                    // Foreign fluid handlers are allowed to differ between simulate and execute.
                    // A changed fluid must be put back, not silently bypass the Gate filter.
                    if (activeFilter != null && !activeFilter.matches(drained)) {
                        if (!restoreFluidBlock(pos, drained)) {
                            pumped = robot.fill(drained, FluidAction.EXECUTE);
                            BCLog.logger.error("Robot pump: filtered fluid changed during drain at " + pos
                                + "; could not restore source, preserved " + pumped + "/" + drained.getAmount());
                        }
                        setSuccess(false);
                        terminate();
                        return;
                    }
                    int acceptedAfterDrain = robot.fill(drained, FluidAction.SIMULATE);
                    if (acceptedAfterDrain < drained.getAmount()) {
                        if (!restoreFluidBlock(pos, drained)) {
                            // Preserve as much fluid as possible if an external world modification prevents rollback.
                            pumped = robot.fill(drained, FluidAction.EXECUTE);
                            BCLog.logger.error("Failed to restore a robot-pumped fluid block at " + pos
                                + "; preserved " + pumped + " of " + drained.getAmount() + " mB in the robot");
                        }
                    } else {
                        pumped = robot.fill(drained, FluidAction.EXECUTE);
                        if (pumped < drained.getAmount()) {
                            FluidStack undo = drained.copy();
                            undo.setAmount(pumped);
                            FluidStack removedAgain = robot.drain(undo, FluidAction.EXECUTE);
                            if (removedAgain.getAmount() == pumped && restoreFluidBlock(pos, drained)) {
                                pumped = 0;
                            } else {
                                robot.fill(removedAgain, FluidAction.EXECUTE);
                                BCLog.logger.error("Robot accepted only " + pumped + " of " + drained.getAmount()
                                    + " mB after pumping at " + pos);
                            }
                        }
                    }
                }
            }
        }

        setSuccess(pumped > 0);
        terminate();
    }


    private boolean restoreFluidBlock(BlockPos pos, FluidStack fluid) {
        if (fluid.isEmpty()) {
            return true;
        }
        FluidTank rollbackTank = new FluidTank(fluid.getAmount());
        rollbackTank.setFluid(fluid.copy());
        //? if <1.20 {
        return FluidUtil.tryPlaceFluid(null, robot.level, null, pos, rollbackTank, fluid.copy());
        //?} else {
        /*?
        return FluidUtil.tryPlaceFluid(null, robot.level(), null, pos, rollbackTank, fluid.copy());
        ?*/
        //?}
    }

    @Override
    public void end() {
        robot.setItemActive(false);
    }

    @Override
    public int getEnergyCost() {
        return 5;
    }

    @Override
    public boolean success() {
        return pumped > 0;
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        if (blockToPump != null) {
            CompoundTag tag = new CompoundTag();
            blockToPump.writeTo(tag);
            nbt.put("blockToPump", tag);
        }
        nbt.putLong("waited", waited);
        nbt.putInt("pumped", pumped);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("blockToPump")) {
            blockToPump = new BlockIndex(nbt.getCompound("blockToPump"));
        }
        waited = nbt.getLong("waited");
        pumped = nbt.getInt("pumped");
    }
}
