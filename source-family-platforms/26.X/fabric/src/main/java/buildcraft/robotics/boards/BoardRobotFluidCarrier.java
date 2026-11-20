package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotWaitForLogistics;
import buildcraft.robotics.scheduler.RobotLogisticsQueue;
import buildcraft.robotics.scheduler.RobotLogisticsScheduler;
import buildcraft.robotics.ai.AIRobotGotoStationAndLoadFluids;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnloadFluids;
import buildcraft.robotics.ai.AIRobotLoadFluids;
import buildcraft.robotics.ai.AIRobotUnloadFluids;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.lib.fluid.FluidStack;

public class BoardRobotFluidCarrier extends RedstoneBoardRobot {
    private RobotLogisticsQueue.Key reservedSource;
    private long sourceToken;
    private DockingStation plannedDestination;
    private long destinationToken;
    private boolean directedUnload;

    /** Simulate the fluid type, gates and receiver capacity before departure. */
    public int canRoute(DockingStation source, DockingStation destination) {
        IFluidFilter filter = ActionRobotFilter.getGateFluidFilter(robot.getLinkedStation());
        if (filter == null || source == null || destination == null
                || source.getFluidInput() == null) return 0;
        int available = AIRobotLoadFluids.load(robot, source, filter, false);
        if (available <= 0) return 0;
        FluidStack preview = source.getFluidInput().drain(1000, true);
        if (preview == null || preview.isEmpty() || !filter.matches(preview)) return 0;
        return Math.min(available, AIRobotUnloadFluids.previewAcceptance(robot, destination, preview, true));
    }

    public boolean canUnloadCargoAt(DockingStation station) {
        return AIRobotUnloadFluids.unload(robot, station, false, true) > 0;
    }

    private void finishDestination() {
        RobotLogisticsScheduler.finishDestination(robot, plannedDestination, destinationToken);
        plannedDestination = null;
        destinationToken = 0L;
        directedUnload = false;
    }


    private void finishSource(boolean success) {
        RobotLogisticsScheduler.finish(robot, reservedSource, sourceToken, success);
        reservedSource = null;
        sourceToken = 0L;
    }
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
            if (RobotLogisticsScheduler.hasController(robot)) {
                RobotLogisticsScheduler.Dispatch assignment = RobotLogisticsScheduler.acquire(robot);
                if (assignment != null && assignment.request() == null) {
                    reservedSource = assignment.key();
                    sourceToken = assignment.leaseToken();
                    plannedDestination = assignment.destination();
                    destinationToken = assignment.destinationToken();
                    startDelegateAI(new AIRobotGotoStationAndLoadFluids(robot, filter,
                            assignment.station(), sourceToken));
                } else {
                    startDelegateAI(new AIRobotWaitForLogistics(robot));
                }
            } else {
                startDelegateAI(new AIRobotGotoStationAndLoadFluids(robot, filter));
            }
        } else {
            if (plannedDestination != null && (!RobotLogisticsScheduler.ownsDestination(robot,
                    plannedDestination, destinationToken)
                    || !canUnloadCargoAt(plannedDestination))) {
                finishDestination();
            }
            if (plannedDestination == null && RobotLogisticsScheduler.hasController(robot)) {
                RobotLogisticsScheduler.Destination recovered = RobotLogisticsScheduler.recoverDestination(robot);
                if (recovered != null) {
                    plannedDestination = recovered.station();
                    destinationToken = recovered.token();
                }
            }
            if (plannedDestination != null && destinationToken != 0L
                    && RobotLogisticsScheduler.ownsDestination(robot, plannedDestination, destinationToken)
                    && canUnloadCargoAt(plannedDestination)) {
                directedUnload = true;
                startDelegateAI(new AIRobotGotoStationAndUnloadFluids(robot, plannedDestination, true));
            } else {
                finishDestination();
                startDelegateAI(new AIRobotGotoStationAndUnloadFluids(robot, true));
            }
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationAndLoadFluids) {
            finishSource(ai.success());
            if (!ai.success()) {
                finishDestination();
                startDelegateAI(RobotLogisticsScheduler.hasController(robot)
                        ? new AIRobotWaitForLogistics(robot) : new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoStationAndUnloadFluids) {
            boolean retryNative = !ai.success() && directedUnload && robotHasFluid();
            finishDestination();
            if (retryNative) {
                startDelegateAI(new AIRobotGotoStationAndUnloadFluids(robot, true));
            } else if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotWaitForLogistics) {
            if (!ai.success() && RobotLogisticsScheduler.hasController(robot)) {
                startDelegateAI(new AIRobotGotoStationAndLoadFluids(robot,
                        ActionRobotFilter.getGateFluidFilter(robot.getLinkedStation())));
            } else {
                update();
            }
        } else if (ai instanceof AIRobotGotoSleep) {
            terminate();
        }
    }

    @Override
    public void end() {
        finishSource(false);
        finishDestination();
    }

    @Override
    public void onUnload() {
        finishSource(false);
        finishDestination();
        super.onUnload();
    }

    private boolean robotHasFluid() {
        FluidStack tank = robot.getFluidInTank(0);
        return !tank.isEmpty() && tank.getAmount() > 0;
    }
}
