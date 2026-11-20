package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotWaitForLogistics;
import buildcraft.robotics.scheduler.RobotLogisticsQueue;
import buildcraft.robotics.scheduler.RobotLogisticsScheduler;
import buildcraft.robotics.ai.AIRobotGotoStationAndLoad;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnload;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotUnload;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import net.minecraft.world.item.ItemStack;
import buildcraft.robotics.statements.ActionRobotFilter;

public class BoardRobotCarrier extends RedstoneBoardRobot {
    private RobotLogisticsQueue.Key reservedSource;
    private long sourceToken;
    private DockingStation plannedDestination;
    private long destinationToken;
    private boolean directedUnload;

    /** A representative item for a two-sided, preflight-only logistics route. */
    public ItemStack previewSource(DockingStation source) {
        IStackFilter filter = ActionRobotFilter.getGateFilter(robot.getLinkedStation());
        return filter == null ? ItemStack.EMPTY : AIRobotLoad.previewCandidate(source, filter, robot.getMaxStackSize());
    }

    public java.util.List<ItemStack> previewSources(DockingStation source) {
        IStackFilter filter = ActionRobotFilter.getGateFilter(robot.getLinkedStation());
        return filter == null ? java.util.List.of()
                : AIRobotLoad.previewCandidates(source, filter, robot.getMaxStackSize(), 4);
    }

    public int canRoute(DockingStation destination, ItemStack example) {
        return AIRobotUnload.previewAcceptance(robot, destination, example, true);
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
    public BoardRobotCarrier(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("carrier").nbt();
    }

    @Override
    public void update() {
        if (!robot.containsItems()) {
            IStackFilter filter = ActionRobotFilter.getGateFilter(robot.getLinkedStation());
            if (RobotLogisticsScheduler.hasController(robot)) {
                RobotLogisticsScheduler.Dispatch assignment = RobotLogisticsScheduler.acquire(robot);
                if (assignment != null && assignment.request() == null) {
                    reservedSource = assignment.key();
                    sourceToken = assignment.leaseToken();
                    plannedDestination = assignment.destination();
                    destinationToken = assignment.destinationToken();
                    ItemStack preview = assignment.itemPreview();
                    // Restrict this load to the type that the selected receiver
                    // was simulated to accept. Other cargo still uses native AI.
                    IStackFilter directedFilter = preview == null || preview.isEmpty() ? filter : stack -> {
                        //? if <1.21 {
                        return ItemStack.isSameItemSameTags(preview, stack);
                        //?} else {
                        /*?
                        return ItemStack.isSameItemSameComponents(preview, stack);
                        ?*/
                        //?}
                    };
                    startDelegateAI(new AIRobotGotoStationAndLoad(robot, directedFilter,
                            AIRobotLoad.ANY_QUANTITY, assignment.station(), sourceToken));
                } else {
                    startDelegateAI(new AIRobotWaitForLogistics(robot));
                }
            } else {
                startDelegateAI(new AIRobotGotoStationAndLoad(robot, filter, AIRobotLoad.ANY_QUANTITY));
            }
        } else {
            if (plannedDestination != null && (!RobotLogisticsScheduler.ownsDestination(robot,
                    plannedDestination, destinationToken)
                    || !AIRobotUnload.unload(robot, plannedDestination, false, true))) {
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
                    && AIRobotUnload.unload(robot, plannedDestination, false, true)) {
                directedUnload = true;
                startDelegateAI(new AIRobotGotoStationAndUnload(robot, plannedDestination, true));
            } else {
                finishDestination();
                startDelegateAI(new AIRobotGotoStationAndUnload(robot, true));
            }
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoStationAndLoad) {
            finishSource(ai.success());
            if (!ai.success()) {
                finishDestination();
                startDelegateAI(RobotLogisticsScheduler.hasController(robot)
                        ? new AIRobotWaitForLogistics(robot) : new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotGotoStationAndUnload) {
            boolean retryNative = !ai.success() && directedUnload && robot.containsItems();
            finishDestination();
            if (retryNative) {
                // The planned recipient filled up or its path became blocked.
                // Retain physical cargo and allow the established AI to reroute.
                startDelegateAI(new AIRobotGotoStationAndUnload(robot, true));
            } else if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotWaitForLogistics) {
            if (!ai.success() && RobotLogisticsScheduler.hasController(robot)) {
                startDelegateAI(new AIRobotGotoStationAndLoad(robot,
                        ActionRobotFilter.getGateFilter(robot.getLinkedStation()), AIRobotLoad.ANY_QUANTITY));
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
}
