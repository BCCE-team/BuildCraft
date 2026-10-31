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
import net.neoforged.neoforge.fluids.FluidStack;
//? if mc_26_x && (>=26.3) {
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import buildcraft.robotics.compat.RobotFluidResourceHandler263;
import buildcraft.transport.internal.pipe.FluidAction;
import buildcraft.lib.compat.NbtCompat;
//? } else {
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
//?}

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

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void start() {
        if (blockToPump == null) {
            setSuccess(false);
            terminate();
            return;
        }
        robot.aimItemAt(blockToPump.toBlockPos());
        robot.setItemActive(true);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
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
        if (!robot.level().isLoaded(pos)) {
            setSuccess(false);
            terminate();
            return;
        }
        if (!RobotAutomationSupport.permitsBlock(
            robot, robot.level(), pos, WorldOperationKind.FLUID_DRAIN, OperationMode.EXECUTE
        )) {
            setSuccess(false);
            terminate();
            return;
        }

        FluidStack simulated = BlockUtil.drainBlock(robot.level(), pos, false);
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
                FluidStack drained = BlockUtil.drainBlock(robot.level(), pos, true);
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
//? if mc_26_x && (>=26.3) {
        FluidStack placed = FluidUtil.tryPlaceFluid(
                RobotFluidResourceHandler263.forRollback(fluid), null, robot.level(), pos, true, null);
        return !placed.isEmpty() && placed.getAmount() == fluid.getAmount();
//? } else {
        FluidTank rollbackTank = new FluidTank(fluid.getAmount());
        rollbackTank.setFluid(fluid.copy());
        return FluidUtil.tryPlaceFluid(null, robot.level(), null, pos, rollbackTank, fluid.copy());
//?}
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void end() {
        robot.setItemActive(false);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public int getEnergyCost() {
        return 5;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean success() {
        return pumped > 0;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public boolean canLoadFromNBT() {
        return true;
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void writeSelfToNBT(CompoundTag nbt) {
        if (blockToPump != null) {
            CompoundTag tag = new CompoundTag();
            blockToPump.writeTo(tag);
            nbt.put("blockToPump", tag);
        }
        nbt.putLong("waited", waited);
        nbt.putInt("pumped", pumped);
    }

//? if mc_26_x && (>=26.3) {
//? } else {
    @Override
//?}
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("blockToPump")) {
//? if mc_26_x && (>=26.3) {
            blockToPump = new BlockIndex(NbtCompat.getCompound(nbt, "blockToPump"));
//? } else {
            blockToPump = new BlockIndex(nbt.getCompound("blockToPump"));
//?}
        }
//? if mc_26_x && (>=26.3) {
        waited = NbtCompat.getLong(nbt, "waited");
        pumped = NbtCompat.getInt(nbt, "pumped");
//? } else {
        waited = nbt.getLong("waited");
        pumped = nbt.getInt("pumped");
//?}
    }
}
