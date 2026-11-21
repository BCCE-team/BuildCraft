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
import buildcraft.robotics.scheduler.ProcessorCandidateBoard;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnloadFluids;
import buildcraft.robotics.ai.AIRobotPumpBlock;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.statements.ActionRobotFilter;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import buildcraft.lib.fluid.FluidStack;
import buildcraft.transport.internal.pipe.FluidAction;
import buildcraft.lib.compat.NbtCompat;

public class BoardRobotPump extends RedstoneBoardRobot implements ProcessorCandidateBoard {
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
            // Finish the useful drain operation, not merely the route to it.
            if (blockFound != null) {
                RobotProcessorWorkScheduler.executionFinished(robot, blockFound.toBlockPos(), ai.success());
            }
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

    @Override
    public String processorWorkCategory() {
        return getNBTHandler().getID();
    }

    @Override
    public boolean processorSupportsQueue() {
        return true;
    }

    /** A cheap, robot-independent candidate predicate for chunk palettes. */
    @Override
    public boolean processorPaletteMightContain(BlockState state) {
        return !state.getFluidState().isEmpty();
    }

    @Override
    public boolean processorSharedScanPredicate() {
        return true;
    }

    @Override
    public boolean processorDiscoveryCandidate(Level level, BlockPos pos, BlockState state) {
        return level.isLoaded(pos) && processorPaletteMightContain(state);
    }

    @Override
    public boolean processorDiscoveryCandidate(Level level, BlockPos pos) {
        return level.isLoaded(pos) && processorPaletteMightContain(level.getBlockState(pos));
    }

    @Override
    public boolean processorScanCandidate(Level level, BlockPos pos) {
        return processorDiscoveryCandidate(level, pos);
    }

    /** Gate filter, tank capacity and actual drainability are rechecked per robot. */
    @Override
    public boolean processorTargetEligible(Level level, BlockPos pos) {
        return processorPlanningEligible(level, pos);
    }

    @Override
    public boolean processorPlanningEligible(Level level, BlockPos pos) {
        if (isTankFull() || !level.isLoaded(pos)) return false;
        updateFilter();
        // Native search must not take an already claimed source, but the
        // controller can renew the claim that THIS robot is executing.
        if (robot.getRegistry() != null) {
            ResourceIdBlock resource = new ResourceIdBlock(pos);
            if (robot.getRegistry().isTaken(resource)
                    && robot.getRegistry().robotIdTaking(resource) != robot.getRobotId()) return false;
        }
        FluidStack fluid = BlockUtil.drainBlock(level, pos, false);
        return !fluid.isEmpty() && robot.fill(fluid, FluidAction.SIMULATE) >= fluid.getAmount()
                && matchesGateFilter(fluid);
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
