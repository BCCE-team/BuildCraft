package buildcraft.robotics.boards;

import java.util.ArrayList;
import java.util.List;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceId;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.StackRequest;
import buildcraft.robotics.ai.AIRobotDeliverRequested;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndLoad;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnload;
import buildcraft.robotics.ai.AIRobotSearchStackRequest;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.lib.inventory.filter.ArrayStackOrListFilter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Classic BuildCraft Delivery robot: fulfils active station item requests. */
public class BoardRobotDelivery extends RedstoneBoardRobot {
    private final List<ItemStack> deliveryBlacklist = new ArrayList<>();
    private StackRequest currentRequest;

    public BoardRobotDelivery(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("delivery").nbt();
    }

    @Override
    public void update() {
        if (currentRequest != null && robot.containsItems()) {
            startDelegateAI(new AIRobotDeliverRequested(robot, currentRequest));
            return;
        }

        if (robot.containsItems()) {
            startDelegateAI(new AIRobotGotoStationAndUnload(robot));
            return;
        }

        if (currentRequest == null) {
            startDelegateAI(new AIRobotSearchStackRequest(robot, ActionRobotFilter.getGateFilter(robot.getLinkedStation()), deliveryBlacklist));
        } else {
            startDelegateAI(new AIRobotGotoStationAndLoad(robot,
                    new ArrayStackOrListFilter(currentRequest.getStack()), currentRequest.getStack().getCount()));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchStackRequest search) {
            if (!ai.success()) {
                deliveryBlacklist.clear();
                startDelegateAI(new AIRobotGotoSleep(robot));
                return;
            }

            currentRequest = search.request;
            //? if <1.20 {
            DockingStation station = currentRequest == null ? null : currentRequest.getStation(robot.level);
            //?} else {
            /*?
            DockingStation station = currentRequest == null ? null : currentRequest.getStation(robot.level());
            ?*/
            //?}
            if (station == null || !station.take(robot)) {
                releaseCurrentRequest();
            }
        } else if (ai instanceof AIRobotGotoStationAndLoad) {
            if (!ai.success()) {
                if (currentRequest != null) {
                    deliveryBlacklist.add(currentRequest.getStack().copy());
                }
                releaseCurrentRequest();
            } else {
                startDelegateAI(new AIRobotDeliverRequested(robot, currentRequest));
            }
        } else if (ai instanceof AIRobotDeliverRequested) {
            releaseCurrentRequest();
        } else if (ai instanceof AIRobotGotoStationAndUnload) {
            startDelegateAI(new AIRobotGotoSleep(robot));
        } else if (ai instanceof AIRobotGotoSleep) {
            terminate();
        }
    }

    private void releaseCurrentRequest() {
        if (currentRequest == null) {
            return;
        }

        //? if <1.20 {
        ResourceId resourceId = currentRequest.getResourceId(robot.level);
        //?} else {
        /*?
        ResourceId resourceId = currentRequest.getResourceId(robot.level());
        ?*/
        //?}
        if (resourceId != null) {
            robot.getRegistry().release(resourceId);
        }

        //? if <1.20 {
        DockingStation station = currentRequest.getStation(robot.level);
        //?} else {
        /*?
        DockingStation station = currentRequest.getStation(robot.level());
        ?*/
        //?}
        if (station != null) {
            station.release(robot);
        }
        currentRequest = null;
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        super.writeSelfToNBT(nbt);
        if (currentRequest != null) {
            CompoundTag requestTag = new CompoundTag();
            currentRequest.writeToNBT(requestTag);
            nbt.put("currentRequest", requestTag);
        }
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        super.loadSelfFromNBT(nbt);
        if (nbt.contains("currentRequest")) {
            currentRequest = StackRequest.loadFromNBT(nbt.getCompound("currentRequest"));
        }
    }
}
