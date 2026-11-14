package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndLoadFluids;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnloadFluids;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.lib.fluid.FluidStack;

public class BoardRobotFluidCarrier extends RedstoneBoardRobot {
    public BoardRobotFluidCarrier(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("fluid_carrier").nbt();
    }

    @Override
    public void update() {
        if (!robotHasFluid()) {
            IFluidFilter filter = ActionRobotFilter.getGateFluidFilter(robot.getLinkedStation());
            startDelegateAI(new AIRobotGotoStationAndLoadFluids(robot, filter));
        } else {
            startDelegateAI(new AIRobotGotoStationAndUnloadFluids(robot, true));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationAndLoadFluids) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoStationAndUnloadFluids) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoSleep) {
            terminate();
        }
    }

    private boolean robotHasFluid() {
        FluidStack tank = robot.getFluidInTank(0);
        return !tank.isEmpty() && tank.getAmount() > 0;
    }
}
