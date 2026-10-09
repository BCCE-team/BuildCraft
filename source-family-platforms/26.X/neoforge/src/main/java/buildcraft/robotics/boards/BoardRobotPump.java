//? source if >=26.3
package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.lib.inventory.filter.PassThroughFluidFilter;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnloadFluids;
import buildcraft.robotics.ai.AIRobotPumpBlock;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.statements.ActionRobotFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;
import buildcraft.transport.internal.pipe.FluidAction;
import buildcraft.lib.compat.NbtCompat;

public class BoardRobotPump extends RedstoneBoardRobot {
    private BlockIndex blockFound;
    private IFluidFilter fluidFilter;

    public BoardRobotPump(EntityRobotBase robot) {
        super(robot);
    }

    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("pump").nbt();
    }

    public void update() {
        if (isTankFull()) {
            startDelegateAI(new AIRobotGotoStationAndUnloadFluids(robot));
            return;
        }

        updateFilter();
        startDelegateAI(new AIRobotSearchAndGotoBlock(robot, false, this::matchesPumpTarget));
    }

    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchAndGotoBlock search) {
            if (search.success()) {
                blockFound = search.getBlockFound();
                startDelegateAI(new AIRobotPumpBlock(robot, blockFound));
            } else if (hasFluidInTank()) {
                startDelegateAI(new AIRobotGotoStationAndUnloadFluids(robot));
            } else {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotPumpBlock) {
            releaseBlockFound();

            if (isTankFull()) {
                startDelegateAI(new AIRobotGotoStationAndUnloadFluids(robot));
            } else if (!ai.success() && hasFluidInTank()) {
                startDelegateAI(new AIRobotGotoStationAndUnloadFluids(robot));
            }
        } else if (ai instanceof AIRobotGotoStationAndUnloadFluids) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        }
    }

    public void end() {
        releaseBlockFound();
    }

    private boolean isTankFull() {
        FluidStack tank = robot.getFluidInTank(0);
        return !tank.isEmpty() && tank.getAmount() >= robot.getTankCapacity(0);
    }

    private boolean hasFluidInTank() {
        FluidStack tank = robot.getFluidInTank(0);
        return !tank.isEmpty() && tank.getAmount() > 0;
    }

    private boolean matchesPumpTarget(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        if (robot.getRegistry() != null && robot.getRegistry().isTaken(new ResourceIdBlock(pos))) {
            return false;
        }

        FluidStack fluid = BlockUtil.drainBlock(level, pos, false);
        if (fluid.isEmpty()) {
            return false;
        }
        if (robot.fill(fluid, FluidAction.SIMULATE) < fluid.getAmount()) {
            return false;
        }
        return matchesGateFilter(fluid);
    }

    private void releaseBlockFound() {
        if (blockFound != null) {
            if (robot.getRegistry() != null) {
                robot.getRegistry().release(new ResourceIdBlock(blockFound));
            }
            blockFound = null;
        }
    }

    private void updateFilter() {
        fluidFilter = ActionRobotFilter.getGateFluidFilter(robot.getLinkedStation());
        if (fluidFilter instanceof PassThroughFluidFilter) {
            fluidFilter = null;
        }
    }

    private boolean matchesGateFilter(FluidStack fluid) {
        return fluidFilter == null || fluidFilter.matches(fluid);
    }

    public boolean canLoadFromNBT() {
        return true;
    }

    public void writeSelfToNBT(CompoundTag nbt) {
        if (blockFound != null) {
            CompoundTag tag = new CompoundTag();
            blockFound.writeTo(tag);
            nbt.put("blockFound", tag);
        }
    }

    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("blockFound")) {
            blockFound = new BlockIndex(NbtCompat.getCompound(nbt, "blockFound"));
        }
    }
}
