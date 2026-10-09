package buildcraft.robotics.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.api.v2.request.ItemRequest;
import buildcraft.api.v2.request.RequestProvider;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceId;
import buildcraft.lib.inventory.filter.PassThroughStackFilter;
import buildcraft.lib.misc.StackUtil;
import buildcraft.robotics.IStationFilter;
import buildcraft.robotics.StackRequest;
import net.minecraft.world.item.ItemStack;

/** Searches for an active station item request that a Delivery robot can fulfil. */
public class AIRobotSearchStackRequest extends AIRobot {
    public StackRequest request;
    public DockingStation station;

    private Collection<ItemStack> blackList;
    private IStackFilter filter;

    public AIRobotSearchStackRequest(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotSearchStackRequest(EntityRobotBase robot, IStackFilter filter, Collection<ItemStack> blackList) {
        this(robot);
        this.filter = filter == null ? new PassThroughStackFilter() : filter;
        this.blackList = blackList;
    }

    @Override
    public void start() {
        startDelegateAI(new AIRobotSearchStation(robot, new StationProviderFilter(), robot.getZoneToWork()));
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchStation search) {
            if (ai.success()) {
                station = search.targetStation;
                request = getOrderFromRequestingStation(station, true);
            }
            terminate();
        }
    }

    @Override
    public boolean success() {
        return request != null;
    }

    private boolean isBlacklisted(ItemStack stack) {
        if (stack == null || stack.isEmpty() || blackList == null) {
            return false;
        }
        for (ItemStack black : blackList) {
            if (!black.isEmpty() && StackUtil.matchesStackOrList(black, stack)) {
                return true;
            }
        }
        return false;
    }

    private StackRequest getOrderFromRequestingStation(DockingStation station, boolean take) {
        for (StackRequest req : getAvailableRequests(station)) {
            ItemStack requestedStack = req.getStack();
            if (!requestedStack.isEmpty() && !isBlacklisted(requestedStack) && filter.matches(requestedStack)) {
                req.setStation(station);
                if (!take) {
                    return req;
                }

                //? if <1.20 {
                ResourceId resourceId = req.getResourceId(robot.level);
                //?} else {
                /*?
                ResourceId resourceId = req.getResourceId(robot.level());
                ?*/
                //?}
                if (resourceId != null && robot.getRegistry().take(resourceId, robot)) {
                    return req;
                }
            }
        }
        return null;
    }

    private Collection<StackRequest> getAvailableRequests(DockingStation station) {
        List<StackRequest> result = new ArrayList<>();
        if (station == null) {
            return result;
        }

        RequestProvider provider = station.getRequestProvider();
        if (provider == null) {
            return result;
        }

        for (ItemRequest definition : provider.requests()) {
            ItemStack stack = definition.matcher().examples().stream()
                .filter(example -> example != null && !example.isEmpty())
                .findFirst()
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
                continue;
            }
            int requestedCount = Math.max(definition.minimum(), Math.min(definition.maximum(), stack.getMaxStackSize()));
            stack.setCount(Math.max(1, requestedCount));

            StackRequest req = new StackRequest(provider, definition.id(), stack);
            req.setStation(station);
            //? if <1.20 {
            ResourceId resourceId = req.getResourceId(robot.level);
            //?} else {
            /*?
            ResourceId resourceId = req.getResourceId(robot.level());
            ?*/
            //?}
            if (resourceId != null && !robot.getRegistry().isTaken(resourceId)) {
                result.add(req);
            }
        }
        return result;
    }

    private class StationProviderFilter implements IStationFilter {
        @Override
        public boolean matches(DockingStation station) {
            return getOrderFromRequestingStation(station, false) != null;
        }
    }
}
