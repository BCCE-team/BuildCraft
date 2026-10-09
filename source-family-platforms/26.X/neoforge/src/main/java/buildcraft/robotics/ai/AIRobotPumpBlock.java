//? source if >=26.3
package buildcraft.robotics.ai;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.lib.misc.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.fluid.FluidUtil;
import buildcraft.robotics.compat.RobotFluidResourceHandler263;
import buildcraft.transport.internal.pipe.FluidAction;
import buildcraft.lib.compat.NbtCompat;

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

    public void start() {
        if (blockToPump == null) {
            setSuccess(false);
            terminate();
            return;
        }
        robot.aimItemAt(blockToPump.toBlockPos());
        robot.setItemActive(true);
    }

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
        if (!simulated.isEmpty()) {
            int accepted = robot.fill(simulated, FluidAction.SIMULATE);
            // A source block is indivisible here. If the robot cannot hold the complete simulated drain, leave the
            // world block untouched and let the board choose another target or unload its current contents.
            if (accepted >= simulated.getAmount()) {
                FluidStack drained = BlockUtil.drainBlock(robot.level(), pos, true);
                if (!drained.isEmpty()) {
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
        FluidStack placed = FluidUtil.tryPlaceFluid(
                RobotFluidResourceHandler263.forRollback(fluid), null, robot.level(), pos, true, null);
        return !placed.isEmpty() && placed.getAmount() == fluid.getAmount();
    }

    public void end() {
        robot.setItemActive(false);
    }

    public int getEnergyCost() {
        return 5;
    }

    public boolean success() {
        return pumped > 0;
    }

    public boolean canLoadFromNBT() {
        return true;
    }

    public void writeSelfToNBT(CompoundTag nbt) {
        if (blockToPump != null) {
            CompoundTag tag = new CompoundTag();
            blockToPump.writeTo(tag);
            nbt.put("blockToPump", tag);
        }
        nbt.putLong("waited", waited);
        nbt.putInt("pumped", pumped);
    }

    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("blockToPump")) {
            blockToPump = new BlockIndex(NbtCompat.getCompound(nbt, "blockToPump"));
        }
        waited = NbtCompat.getLong(nbt, "waited");
        pumped = NbtCompat.getInt(nbt, "pumped");
    }
}
