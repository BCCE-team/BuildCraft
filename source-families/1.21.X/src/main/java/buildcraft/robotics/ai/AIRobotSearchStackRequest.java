package buildcraft.robotics.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
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
    // A station is queried in both filter.matches() and after selection.
    // Preserve its request snapshot for this one search rather than repeating
    // a potentially 8192-item registry scan for every matching station.
    private final Map<DockingStation, Collection<StackRequest>> stationRequests = new IdentityHashMap<>();
    private final Map<ItemRequest, ItemStack> representativeCache = new IdentityHashMap<>();

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

                ResourceId resourceId = req.getResourceId(robot.level());
                if (resourceId != null && robot.getRegistry().take(resourceId, robot)) {
                    return req;
                }
            }
        }
        return null;
    }

    /** Check the actual matcher, not merely the first advisory example supplied by an addon. */
    private ItemStack selectMatchingStack(ItemRequest definition) {
        ItemStack cached = representativeCache.get(definition);
        if (cached != null) return cached.copy();
        for (ItemStack example : definition.matcher().examples()) {
            if (acceptsRequest(definition, example)) {
                representativeCache.put(definition, example.copy());
                return example.copy();
            }
        }
        // Some composed predicates have no examples at all. Inspect the registered items
        // for a compatible non-empty representative, bounded per request to avoid abuse.
        int scanned = 0;
        for (var item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (++scanned > 8192) break;
            ItemStack candidate = new ItemStack(item);
            if (acceptsRequest(definition, candidate)) {
                representativeCache.put(definition, candidate.copy());
                return candidate;
            }
        }
        representativeCache.put(definition, ItemStack.EMPTY);
        return ItemStack.EMPTY;
    }

    private boolean acceptsRequest(ItemRequest definition, ItemStack candidate) {
        if (candidate == null || candidate.isEmpty() || isBlacklisted(candidate) || !filter.matches(candidate)) {
            return false;
        }
        try {
            return definition.matcher().matches(candidate);
        } catch (RuntimeException invalidMatcher) {
            return false;
        }
    }

    private Collection<StackRequest> getAvailableRequests(DockingStation station) {
        List<StackRequest> result = new ArrayList<>();
        if (station == null) {
            return result;
        }
        Collection<StackRequest> cached = stationRequests.get(station);
        if (cached != null) return cached;

        RequestProvider provider = station.getRequestProvider();
        if (provider == null) {
            return result;
        }

        if (provider.requests() == null) return result;
        List<ItemRequest> ordered = provider.requests().stream()
            .filter(java.util.Objects::nonNull)
            .sorted(java.util.Comparator.comparingInt(ItemRequest::priority).reversed()
                .thenComparing(definition -> definition.id().toString()))
            .toList();
        for (ItemRequest definition : ordered) {
            ItemStack stack = selectMatchingStack(definition);
            if (stack.isEmpty()) continue;
            int requestedCount = Math.max(definition.minimum(), Math.min(definition.maximum(), stack.getMaxStackSize()));
            stack.setCount(Math.max(1, requestedCount));

            StackRequest req = new StackRequest(provider, definition.id(), stack);
            req.setStation(station);
            ResourceId resourceId = req.getResourceId(robot.level());
            if (resourceId != null && !robot.getRegistry().isTaken(resourceId)) {
                result.add(req);
            }
        }
        stationRequests.put(station, result);
        return result;
    }

    private class StationProviderFilter implements IStationFilter {
        @Override
        public boolean matches(DockingStation station) {
            return getOrderFromRequestingStation(station, false) != null;
        }
    }
}
