package buildcraft.robotics.scheduler;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;

import buildcraft.api.v2.request.ItemRequest;
import buildcraft.api.v2.request.RequestProvider;
import buildcraft.lib.internal.area.IZone;
import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.StackRequest;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.ai.AIRobotLoad;
import buildcraft.robotics.ai.AIRobotLoadFluids;
import buildcraft.robotics.boards.BoardRobotCarrier;
import buildcraft.robotics.boards.BoardRobotDelivery;
import buildcraft.robotics.boards.BoardRobotFluidCarrier;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.ResourceId;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import buildcraft.robotics.statements.ActionRobotFilter;
import buildcraft.robotics.statements.ActionStationForbidRobot;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Shared, bounded logistics demand discovery for Carrier, Fluid Carrier and Delivery.
 *
 * <p>Item/fluid carrier jobs are reservations of a <em>source</em> station,
 * NOT fictional reservations of inventory contents. A job becomes a physical
 * trip only once the existing AIRobotLoad/LoadFluids actually transfers cargo;
 * the existing unload AI then chooses an eligible destination for that cargo.
 * A request job is a destination + request ID: an existing IRobotRegistry
 * ResourceId claim, followed by DockingStation.take(), is authoritative.
 * Neither the queue nor this scanner ever transfers resources or loads chunks.
 * Claims are transient across world reloads; actual carried cargo and accepted
 * delivery requests remain under the existing robot NBT lifecycle.</p>
 */
public final class RobotLogisticsScheduler {
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final int MAX_STATIONS_PER_PASS = 128;
    private static final int MAX_REQUESTS_PER_PASS = 96;
    private static final long LEASE_TICKS = 120L;
    private static final long FAILURE_COOLDOWN_TICKS = 100L;
    private static final long DESTINATION_LEASE_TICKS = 2400L;
    private static final int DESTINATIONS_PER_SOURCE = 16;
    private static final int ROUTE_PROBES_PER_PASS = 512;

    /** A route is a simulation and a soft destination lease, never a transfer. */
    public record Dispatch(DockingStation station, StackRequest request,
                           RobotLogisticsQueue.Key key, long leaseToken,
                           DockingStation destination, ItemStack itemPreview,
                           long destinationToken) { }

    public record Destination(DockingStation station, long token) { }
    private record RouteOffer(DockingStation destination, ItemStack preview, int units) { }


    private static final class WorldState {
        final RobotLogisticsQueue jobs = new RobotLogisticsQueue();
        final RobotLogisticsRouteLeases routes = new RobotLogisticsRouteLeases();
        final Map<Long, WeakReference<RobotLogisticsScheduler>> panels = new HashMap<>();
        final Map<Long, Map<RobotLogisticsQueue.Key, Long>> failed = new HashMap<>();
        long lastExpiration = Long.MIN_VALUE;

        void expire(long tick) {
            if (lastExpiration == tick) return;
            lastExpiration = tick;
            jobs.expire(tick);
            routes.expire(tick);
            panels.entrySet().removeIf(entry -> {
                RobotLogisticsScheduler scheduler = entry.getValue().get();
                return scheduler == null || scheduler.panel.isRemoved();
            });
            failed.values().forEach(map -> map.entrySet().removeIf(entry -> entry.getValue() <= tick));
            failed.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        }

        boolean cooledDown(long robotId, RobotLogisticsQueue.Key key, long tick) {
            return failed.getOrDefault(robotId, Map.of()).getOrDefault(key, 0L) > tick;
        }
    }

    private static final Map<Level, WorldState> WORLDS = new WeakHashMap<>();

    private record NearestStation(DockingStation station, double distanceSqr) { }

    private final TileRobotProcessor panel;
    private long nextScan;
    private long nextValidation;
    private int stationRotation;

    public RobotLogisticsScheduler(TileRobotProcessor panel) {
        this.panel = panel;
    }

    private long panelId() { return panel.getBlockPos().asLong(); }

    private static Level robotLevel(EntityRobotBase robot) {
        //? if <1.20 {
        return robot.level;
        //?} else {
        /*?
        return robot.level();
        ?*/
        //?}
    }

    private static RobotLogisticsQueue.Kind role(EntityRobotBase robot) {
        if (robot == null) return null;
        if (robot.getBoard() instanceof BoardRobotCarrier) return RobotLogisticsQueue.Kind.ITEM_SOURCE;
        if (robot.getBoard() instanceof BoardRobotFluidCarrier) return RobotLogisticsQueue.Kind.FLUID_SOURCE;
        if (robot.getBoard() instanceof BoardRobotDelivery) return RobotLogisticsQueue.Kind.ITEM_REQUEST;
        return null;
    }

    private static boolean hasCargo(EntityRobotBase robot) {
        RobotLogisticsQueue.Kind kind = role(robot);
        if (kind == RobotLogisticsQueue.Kind.ITEM_SOURCE) return robot.containsItems();
        if (kind == RobotLogisticsQueue.Kind.FLUID_SOURCE) {
            var stack = robot.getFluidInTank(0);
            return stack != null && !stack.isEmpty() && stack.getAmount() > 0;
        }
        return kind == RobotLogisticsQueue.Kind.ITEM_REQUEST && robot.containsItems();
    }

    /** The board is still responsible for the physical cargo and its destination. */
    private static boolean ready(EntityRobotBase robot) {
        if (robot == null || robot.isRemoved() || role(robot) == null
                || robot.getRobotId() <= 0L || robot.getLinkedStation() == null
                || robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY || hasCargo(robot)) return false;
        if (robot.getBoard() instanceof BoardRobotDelivery delivery && delivery.hasCurrentRequest()) return false;
        AIRobot active = robot.getActiveAI();
        return active != null && active.isWithinWorkChain(robot.getBoard());
    }

    private static RobotLogisticsQueue.Key key(RobotLogisticsQueue.Kind kind, DockingStation station, String requestId) {
        return new RobotLogisticsQueue.Key(kind, station.index().toBlockPos().asLong(),
                station.side() == null ? -1 : station.side().get3DDataValue(), requestId);
    }

    private static DockingStation resolve(IRobotRegistry registry, ServerLevel level, RobotLogisticsQueue.Key key) {
        BlockPos position = BlockPos.of(key.stationPos());
        if (!level.hasChunkAt(position)) return null;
        Direction side = key.side() < 0 ? null : Direction.from3DDataValue(key.side());
        DockingStation station = registry.getStation(position, side);
        return station == null || !station.isInitialized() ? null : station;
    }

    private static boolean stationAllowed(EntityRobotBase robot, DockingStation station, boolean destination) {
        if (station == null || !station.isInitialized() || robot.getRegistry() == null
                || (station.isTaken() && station.robotIdTaking() != robot.getRobotId())
                || ActionStationForbidRobot.isForbidden(station, robot)) return false;
        // Carriers use the load/unload area for BOTH ends of their route.
        // Delivery requests use the work area, matching the native delivery AI.
        IZone zone = destination && role(robot) == RobotLogisticsQueue.Kind.ITEM_REQUEST
                ? robot.getZoneToWork() : robot.getZoneToLoadUnload();
        return zone == null || zone.contains(new Vec3(station.x(), station.y(), station.z()));
    }

    private static boolean mayLoad(EntityRobotBase robot, DockingStation station,
                                   RobotLogisticsQueue.Kind kind) {
        if (!stationAllowed(robot, station, false)) return false;
        if (kind == RobotLogisticsQueue.Kind.ITEM_SOURCE) {
            IStackFilter filter = ActionRobotFilter.getGateFilter(robot.getLinkedStation());
            return filter != null && AIRobotLoad.load(robot, station, filter, AIRobotLoad.ANY_QUANTITY, false);
        }
        if (kind == RobotLogisticsQueue.Kind.FLUID_SOURCE) {
            IFluidFilter filter = ActionRobotFilter.getGateFluidFilter(robot.getLinkedStation());
            return filter != null && AIRobotLoadFluids.load(robot, station, filter, false) > 0;
        }
        return false;
    }

    /**
     * Advisory scheduling only: the source's real inventory is rechecked by
     * AIRobotLoad at transfer time, so concurrent external extraction is safe.
     */
    private StackRequest requestFor(EntityRobotBase robot, DockingStation station, String requestId) {
        if (!stationAllowed(robot, station, true)) return null;
        RequestProvider provider = station.getRequestProvider();
        if (provider == null || provider.requests() == null) return null;
        IStackFilter homeFilter = ActionRobotFilter.getGateFilter(robot.getLinkedStation());
        for (ItemRequest definition : provider.requests()) {
            if (definition == null || !definition.id().toString().equals(requestId)) continue;
            try {
                for (ItemStack example : definition.matcher().examples()) {
                    if (example == null || example.isEmpty()
                            || (homeFilter != null && !homeFilter.matches(example))
                            || !definition.matcher().matches(example)) continue;
                    ItemStack stack = example.copy();
                    stack.setCount(Math.max(1, Math.max(definition.minimum(),
                            Math.min(definition.maximum(), stack.getMaxStackSize()))));
                    StackRequest request = new StackRequest(provider, definition.id(), stack);
                    request.setStation(station);
                    ResourceId physical = request.getResourceId(robotLevel(robot));
                    if (physical != null && !robot.getRegistry().isTaken(physical)) return request;
                }
            } catch (RuntimeException invalidRequest) {
                // An extension may supply an invalid matcher; leave it to the
                // native request logic rather than poisoning the shared pass.
            }
        }
        return null;
    }

    private static RobotLogisticsScheduler controller(EntityRobotBase robot, WorldState state) {
        RobotLogisticsScheduler selected = null;
        for (WeakReference<RobotLogisticsScheduler> reference : state.panels.values()) {
            RobotLogisticsScheduler candidate = reference.get();
            if (candidate == null || candidate.panel.isRemoved()
                    || !candidate.panel.isRobotLinked(robot.getRobotId())) continue;
            if (selected == null || candidate.panelId() < selected.panelId()) selected = candidate;
        }
        return selected;
    }

    public static boolean hasController(EntityRobotBase robot) {
        if (role(robot) == null) return false;
        WorldState state = WORLDS.get(robotLevel(robot));
        return state != null && controller(robot, state) != null;
    }

    public static boolean hasQueuedJob(EntityRobotBase robot) {
        if (robot == null) return false;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return false;
        RobotLogisticsScheduler owner = controller(robot, state);
        return owner != null && !state.jobs.jobs(owner.panelId(), robot.getRobotId()).isEmpty();
    }

    private static RobotLogisticsRouteLeases.Dock dockKey(DockingStation station) {
        return new RobotLogisticsRouteLeases.Dock(station.index().toBlockPos().asLong(),
                station.side() == null ? -1 : station.side().get3DDataValue());
    }

    private static double distanceSquared(DockingStation a, DockingStation b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Bound preflight to a few nearby loaded receivers. No inventory is changed:
     * item/fluids are offered through the native SIMULATE code paths. The
     * resulting quantity is an estimate, not a guaranteed future delivery.
     */
    private RouteOffer findReceiver(WorldState state, EntityRobotBase robot,
                                    DockingStation source, RobotLogisticsQueue.Kind kind,
                                    Iterable<DockingStation> pool, long now, int[] budget) {
        if (kind == RobotLogisticsQueue.Kind.ITEM_REQUEST) return null;
        List<ItemStack> previews = List.of();
        if (kind == RobotLogisticsQueue.Kind.ITEM_SOURCE) {
            if (!(robot.getBoard() instanceof BoardRobotCarrier board)) return null;
            previews = board.previewSources(source);
            if (previews.isEmpty()) return null;
        }
        List<NearestStation> nearest = new ArrayList<>();
        for (DockingStation station : pool) {
            if (station == null || station == source || !station.isInitialized()
                    || !stationAllowed(robot, station, true)
                    || state.routes.reservedByOther(dockKey(station), robot.getRobotId(), now)) continue;
            if (dockKey(station).equals(dockKey(source))) continue;
            nearest.add(new NearestStation(station, distanceSquared(source, station)));
        }
        nearest.sort(Comparator.comparingDouble(NearestStation::distanceSqr));
        RouteOffer best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        int probed = 0;
        for (NearestStation candidate : nearest) {
            if (probed++ >= DESTINATIONS_PER_SOURCE || budget[0]-- <= 0) break;
            DockingStation destination = candidate.station();
            if (kind == RobotLogisticsQueue.Kind.ITEM_SOURCE) {
                for (ItemStack preview : previews) {
                    int accepted = ((BoardRobotCarrier) robot.getBoard()).canRoute(destination, preview);
                    if (accepted <= 0) continue;
                    double distance = candidate.distanceSqr();
                    if (best == null || distance < bestDistance
                            || (distance == bestDistance && accepted > best.units())) {
                        best = new RouteOffer(destination, preview.copy(), accepted);
                        bestDistance = distance;
                    }
                }
            } else if (robot.getBoard() instanceof BoardRobotFluidCarrier board) {
                int accepted = board.canRoute(source, destination);
                if (accepted <= 0) continue;
                double distance = candidate.distanceSqr();
                if (best == null || distance < bestDistance
                        || (distance == bestDistance && accepted > best.units())) {
                    best = new RouteOffer(destination, ItemStack.EMPTY, accepted);
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static List<DockingStation> stationPool(IRobotRegistry registry, ServerLevel level) {
        List<DockingStation> stations = new ArrayList<>();
        for (DockingStation station : registry.getStations()) {
            if (station != null && station.isInitialized()
                    && level.hasChunkAt(station.index().toBlockPos())) stations.add(station);
        }
        return stations;
    }

    public void tick(ServerLevel level) {
        WorldState state = WORLDS.computeIfAbsent(level, ignored -> new WorldState());
        long now = level.getGameTime();
        state.expire(now);
        WeakReference<RobotLogisticsScheduler> current = state.panels.get(panelId());
        if (current == null || current.get() != this) {
            state.panels.put(panelId(), new WeakReference<>(this));
        }
        IRobotRegistry registry = RobotManager.registryProvider == null ? null
                : RobotManager.registryProvider.getRegistry(level);
        if (registry == null) return;
        List<EntityRobotBase> workers = new ArrayList<>();
        Set<Long> readyIds = new HashSet<>();
        for (long id : panel.getLinkedRobotIds()) {
            EntityRobotBase robot = registry.getLoadedRobot(id);
            if (robot != null && !robot.isRemoved() && robotLevel(robot) == level
                    && controller(robot, state) == this && role(robot) != null
                    && robot.getActiveAI() != null
                    && robot.getActiveAI().isWithinWorkChain(robot.getBoard())) {
                state.routes.renewRobot(panelId(), id, now, DESTINATION_LEASE_TICKS);
            }
            if (ready(robot) && robotLevel(robot) == level && controller(robot, state) == this) {
                workers.add(robot);
                readyIds.add(id);
            } else {
                state.jobs.clearRobot(panelId(), id);
            }
        }
        // Refresh cheap tokens every tick; the potentially expensive capability
        // simulations are bounded to one validation pass per ten ticks.
        boolean validate = now >= nextValidation;
        if (validate) nextValidation = now + 10L;
        for (EntityRobotBase robot : workers) {
            for (RobotLogisticsQueue.Claim job : state.jobs.jobs(panelId(), robot.getRobotId())) {
                DockingStation station = resolve(registry, level, job.key());
                boolean valid = station != null && (!validate
                        || (job.key().kind() == RobotLogisticsQueue.Kind.ITEM_REQUEST
                        ? requestFor(robot, station, job.key().requestId()) != null
                        : mayLoad(robot, station, job.key().kind())));
                if (!valid || !state.jobs.renew(job.key(), panelId(), robot.getRobotId(),
                        job.token(), now, LEASE_TICKS)) {
                    state.jobs.release(job.key(), robot.getRobotId(), job.token());
                }
            }
        }
        if (workers.isEmpty() || now < nextScan) return;
        nextScan = now + SCAN_INTERVAL_TICKS;
        Map<Long, Integer> sizes = new HashMap<>();
        boolean demand = false;
        for (EntityRobotBase robot : workers) {
            int size = state.jobs.size(panelId(), robot.getRobotId());
            sizes.put(robot.getRobotId(), size);
            if (size < panel.getMaxExecutionQueue()) demand = true;
        }
        if (!demand) return;
        // Keep only the nearest 128 candidates in a bounded max heap. Sorting
        // the entire loaded station registry was unnecessary on large servers.
        PriorityQueue<NearestStation> closest = new PriorityQueue<>(
                Comparator.comparingDouble(NearestStation::distanceSqr).reversed());
        for (DockingStation station : registry.getStations()) {
            if (station == null || !station.isInitialized()
                    || !level.hasChunkAt(station.index().toBlockPos())) continue;
            double best = Double.POSITIVE_INFINITY;
            for (EntityRobotBase worker : workers) {
                double dx = station.x() - worker.getX();
                double dy = station.y() - worker.getY();
                double dz = station.z() - worker.getZ();
                best = Math.min(best, dx * dx + dy * dy + dz * dz);
            }
            if (closest.size() < MAX_STATIONS_PER_PASS) {
                closest.add(new NearestStation(station, best));
            } else if (best < closest.peek().distanceSqr()) {
                closest.poll();
                closest.add(new NearestStation(station, best));
            }
        }
        List<NearestStation> ordered = new ArrayList<>(closest);
        ordered.sort(Comparator.comparingDouble(NearestStation::distanceSqr));
        List<DockingStation> receiverPool = new ArrayList<>();
        for (NearestStation candidate : ordered) receiverPool.add(candidate.station());
        int[] routeBudget = {ROUTE_PROBES_PER_PASS};
        int requests = 0;
        int start = ordered.isEmpty() ? 0 : Math.floorMod(stationRotation++, ordered.size());
        for (int offset = 0; offset < ordered.size(); offset++) {
            NearestStation candidate = ordered.get((start + offset) % ordered.size());
            DockingStation station = candidate.station();
            for (RobotLogisticsQueue.Kind kind : List.of(RobotLogisticsQueue.Kind.ITEM_SOURCE,
                    RobotLogisticsQueue.Kind.FLUID_SOURCE)) {
                if (addBest(state, workers, sizes, station, kind, "", now,
                        receiverPool, routeBudget)) continue;
            }
            RequestProvider provider = station.getRequestProvider();
            if (provider == null || provider.requests() == null) continue;
            List<ItemRequest> definitions = new ArrayList<>();
            for (ItemRequest definition : provider.requests()) {
                if (definition != null) definitions.add(definition);
            }
            definitions.sort(Comparator.comparingInt(ItemRequest::priority).reversed()
                    .thenComparing(definition -> definition.id().toString()));
            for (ItemRequest definition : definitions) {
                if (++requests > MAX_REQUESTS_PER_PASS) break;
                addBest(state, workers, sizes, station, RobotLogisticsQueue.Kind.ITEM_REQUEST,
                        definition.id().toString(), now, receiverPool, routeBudget);
            }
            if (requests > MAX_REQUESTS_PER_PASS) break;
        }
    }

    private boolean addBest(WorldState state, List<EntityRobotBase> workers,
                            Map<Long, Integer> sizes, DockingStation station,
                            RobotLogisticsQueue.Kind kind, String requestId, long now,
                            List<DockingStation> receivers, int[] routeBudget) {
        RobotLogisticsQueue.Key jobKey = key(kind, station, requestId);
        if (state.jobs.get(jobKey, now) != null) return false;
        EntityRobotBase best = null;
        int shortestQueue = Integer.MAX_VALUE;
        double shortestDistance = Double.POSITIVE_INFINITY;
        for (EntityRobotBase robot : workers) {
            if (role(robot) != kind || sizes.getOrDefault(robot.getRobotId(), 0) >= panel.getMaxExecutionQueue()
                    || state.cooledDown(robot.getRobotId(), jobKey, now)) continue;
            boolean eligible = kind == RobotLogisticsQueue.Kind.ITEM_REQUEST
                    ? requestFor(robot, station, requestId) != null : mayLoad(robot, station, kind);
            if (!eligible) continue;
            RouteOffer route = kind == RobotLogisticsQueue.Kind.ITEM_REQUEST ? null
                    : findReceiver(state, robot, station, kind, receivers, now, routeBudget);
            if (kind != RobotLogisticsQueue.Kind.ITEM_REQUEST && route == null) continue;
            double dx = station.x() - robot.getX(), dy = station.y() - robot.getY(), dz = station.z() - robot.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (route != null) distance += distanceSquared(station, route.destination());
            int count = sizes.getOrDefault(robot.getRobotId(), 0);
            if (count < shortestQueue || (count == shortestQueue && distance < shortestDistance)) {
                best = robot;
                shortestQueue = count;
                shortestDistance = distance;
            }
        }
        if (best == null) return false;
        if (state.jobs.claim(jobKey, panelId(), best.getRobotId(), panel.getMaxExecutionQueue(), now,
                LEASE_TICKS) == null) return false;
        sizes.merge(best.getRobotId(), 1, Integer::sum);
        return true;
    }

    public static Dispatch acquire(EntityRobotBase robot) {
        if (!ready(robot) || !(robotLevel(robot) instanceof ServerLevel level)) return null;
        WorldState state = WORLDS.get(level);
        if (state == null) return null;
        long now = level.getGameTime();
        state.expire(now);
        RobotLogisticsScheduler scheduler = controller(robot, state);
        if (scheduler == null || robot.getRegistry() == null) return null;
        List<DockingStation> receivers = stationPool(robot.getRegistry(), level);
        int[] routeBudget = {ROUTE_PROBES_PER_PASS};
        for (RobotLogisticsQueue.Claim job : state.jobs.jobs(scheduler.panelId(), robot.getRobotId())) {
            if (job.key().kind() != role(robot)) continue;
            DockingStation station = resolve(robot.getRegistry(), level, job.key());
            StackRequest request = null;
            boolean valid = station != null;
            if (valid && job.key().kind() == RobotLogisticsQueue.Kind.ITEM_REQUEST) {
                request = scheduler.requestFor(robot, station, job.key().requestId());
                valid = request != null;
            } else if (valid) {
                valid = mayLoad(robot, station, job.key().kind());
            }
            if (!valid) {
                state.jobs.release(job.key(), robot.getRobotId(), job.token());
                continue;
            }
            RouteOffer route = job.key().kind() == RobotLogisticsQueue.Kind.ITEM_REQUEST ? null
                    : scheduler.findReceiver(state, robot, station, job.key().kind(),
                            receivers, now, routeBudget);
            if (job.key().kind() != RobotLogisticsQueue.Kind.ITEM_REQUEST && route == null) {
                state.jobs.release(job.key(), robot.getRobotId(), job.token());
                continue;
            }
            RobotLogisticsQueue.Claim active = state.jobs.activate(job.key(), scheduler.panelId(),
                    robot.getRobotId(), now, LEASE_TICKS);
            if (active == null) continue;
            RobotLogisticsRouteLeases.Claim destinationClaim = null;
            if (route != null) {
                destinationClaim = state.routes.reserve(dockKey(route.destination()),
                        scheduler.panelId(), robot.getRobotId(), now, DESTINATION_LEASE_TICKS);
                if (destinationClaim == null) {
                    state.jobs.release(job.key(), robot.getRobotId(), active.token());
                    continue;
                }
            }
            if (request != null) {
                ResourceId id = request.getResourceId(level);
                // The native registry protects even against unconnected delivery
                // robots; the destination station must ALSO be reserved.
                if (id == null || !robot.getRegistry().take(id, robot)) {
                    state.jobs.release(job.key(), robot.getRobotId(), active.token());
                    continue;
                }
                if (!station.take(robot)) {
                    robot.getRegistry().release(id);
                    state.jobs.release(job.key(), robot.getRobotId(), active.token());
                    continue;
                }
                // The physical request claim now supersedes the advisory lease.
                state.jobs.release(job.key(), robot.getRobotId(), active.token());
            }
            if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_LOGISTICS_ASSIGN",
                    "kind=%s source=%s request=%s token=%d",
                    active.key().kind(), station, active.key().requestId(), active.token());
            if (RobotTrace.enabled() && route != null) RobotTrace.event(robot,
                    "PROCESSOR_LOGISTICS_ROUTE", "source=%s dest=%s units=%d token=%d",
                    station, route.destination(), route.units(), destinationClaim.token());
            return new Dispatch(station, request, active.key(), active.token(),
                    route == null ? null : route.destination(),
                    route == null ? ItemStack.EMPTY : route.preview(),
                    destinationClaim == null ? 0L : destinationClaim.token());
        }
        return null;
    }

    /** Check painted/zone/gate policy and exact token just before loading. */
    public static boolean ownsSource(EntityRobotBase robot, DockingStation station,
                                     RobotLogisticsQueue.Kind kind, long token) {
        if (robot == null || station == null || token == 0 || robot.getRegistry() == null
                || !(robotLevel(robot) instanceof ServerLevel level)) return false;
        WorldState state = WORLDS.get(level);
        RobotLogisticsScheduler owner = state == null ? null : controller(robot, state);
        if (owner == null) return false;
        RobotLogisticsQueue.Claim claim = state.jobs.get(key(kind, station, ""), level.getGameTime());
        return claim != null && claim.panelId() == owner.panelId()
                && claim.robotId() == robot.getRobotId() && claim.token() == token && claim.active()
                && mayLoad(robot, station, kind);
    }

    /**
     * Rebuild a receiver for physical cargo after a world reload, interrupted
     * route, or external loading. The cargo itself is managed by the robot's
     * existing NBT. This method only simulates acceptance and leases a dock.
     */
    public static Destination recoverDestination(EntityRobotBase robot) {
        if (robot == null || !hasCargo(robot) || role(robot) == RobotLogisticsQueue.Kind.ITEM_REQUEST
                || !(robotLevel(robot) instanceof ServerLevel level) || robot.getRegistry() == null) return null;
        WorldState state = WORLDS.get(level);
        RobotLogisticsScheduler scheduler = state == null ? null : controller(robot, state);
        if (scheduler == null) return null;
        long now = level.getGameTime();
        state.expire(now);
        List<NearestStation> pool = new ArrayList<>();
        for (DockingStation station : stationPool(robot.getRegistry(), level)) {
            if (!stationAllowed(robot, station, true)
                    || state.routes.reservedByOther(dockKey(station), robot.getRobotId(), now)) continue;
            double dx = station.x() - robot.getX(), dy = station.y() - robot.getY(), dz = station.z() - robot.getZ();
            pool.add(new NearestStation(station, dx * dx + dy * dy + dz * dz));
        }
        pool.sort(Comparator.comparingDouble(NearestStation::distanceSqr));
        int inspected = 0;
        for (NearestStation candidate : pool) {
            if (inspected++ >= MAX_STATIONS_PER_PASS) break;
            DockingStation station = candidate.station();
            boolean suitable = robot.getBoard() instanceof BoardRobotCarrier
                    ? buildcraft.robotics.ai.AIRobotUnload.unload(robot, station, false, true)
                    : robot.getBoard() instanceof BoardRobotFluidCarrier board
                            && board.canUnloadCargoAt(station);
            if (!suitable) continue;
            RobotLogisticsRouteLeases.Claim lease = state.routes.reserve(dockKey(station),
                    scheduler.panelId(), robot.getRobotId(), now, DESTINATION_LEASE_TICKS);
            if (lease == null) continue;
            if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_LOGISTICS_RECOVER",
                    "destination=%s token=%d", station, lease.token());
            return new Destination(station, lease.token());
        }
        return null;
    }

    /** Return true only for the live, generation-matched soft receiver lease. */
    public static boolean ownsDestination(EntityRobotBase robot, DockingStation station, long token) {
        if (robot == null || station == null || token == 0L
                || !(robotLevel(robot) instanceof ServerLevel level)) return false;
        WorldState state = WORLDS.get(level);
        RobotLogisticsScheduler controller = state == null ? null : controller(robot, state);
        return controller != null && stationAllowed(robot, station, true)
                && state.routes.owned(dockKey(station), robot.getRobotId(), token, level.getGameTime());
    }

    public static void finishDestination(EntityRobotBase robot, DockingStation station, long token) {
        if (robot == null || station == null || token == 0L) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state != null && state.routes.release(dockKey(station), robot.getRobotId(), token)
                && RobotTrace.enabled()) {
            RobotTrace.event(robot, "PROCESSOR_LOGISTICS_ROUTE_FINISH",
                    "destination=%s token=%d", station, token);
        }
    }

    /** Exact generation fencing prevents a late AI callback from releasing new work. */
    public static void finish(EntityRobotBase robot, RobotLogisticsQueue.Key key, long token, boolean success) {
        if (robot == null || key == null || token == 0) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null || !state.jobs.release(key, robot.getRobotId(), token)) return;
        if (RobotTrace.enabled()) RobotTrace.event(robot, "PROCESSOR_LOGISTICS_FINISH",
                "kind=%s station=%d success=%s token=%d",
                key.kind(), key.stationPos(), success, token);
        if (!success) {
            state.failed.computeIfAbsent(robot.getRobotId(), ignored -> new HashMap<>())
                    .put(key, robotLevel(robot).getGameTime() + FAILURE_COOLDOWN_TICKS);
        }
    }

    /** Suppress an invalid/unfillable request temporarily after a failed load. */
    public static void failedDelivery(EntityRobotBase robot, RobotLogisticsQueue.Key key) {
        if (robot == null || key == null || key.kind() != RobotLogisticsQueue.Kind.ITEM_REQUEST) return;
        WorldState state = WORLDS.get(robotLevel(robot));
        if (state == null) return;
        state.failed.computeIfAbsent(robot.getRobotId(), ignored -> new HashMap<>())
                .put(key, robotLevel(robot).getGameTime() + FAILURE_COOLDOWN_TICKS);
    }

    public int queueCount(long robotId) {
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        int queued = state == null ? 0 : state.jobs.size(panelId(), robotId);
        if (queued > 0 || RobotManager.registryProvider == null || level == null) return queued;
        IRobotRegistry registry = RobotManager.registryProvider.getRegistry(level);
        EntityRobotBase robot = registry == null ? null : registry.getLoadedRobot(robotId);
        if (robot == null || role(robot) == null) return queued;
        AIRobot ai = robot.getActiveAI();
        if (ai == null || !ai.isWithinWorkChain(robot.getBoard())) return queued;
        return hasCargo(robot) || (robot.getBoard() instanceof BoardRobotDelivery delivery
                && delivery.hasCurrentRequest()) ? 1 : queued;
    }

    public void onQueueLimitChanged() {
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        if (state == null) return;
        for (long id : panel.getLinkedRobotIds()) {
            state.jobs.trim(panelId(), id, panel.getMaxExecutionQueue());
        }
    }

    public void onLinkChanged() {
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        if (state != null) {
            state.jobs.clearPanel(panelId());
            state.routes.clearPanel(panelId());
        }
        nextScan = 0L;
    }

    public void onRemoved() {
        Level level = panel.getLevel();
        WorldState state = level == null ? null : WORLDS.get(level);
        if (state == null) return;
        state.jobs.clearPanel(panelId());
        state.routes.clearPanel(panelId());
        WeakReference<RobotLogisticsScheduler> ref = state.panels.get(panelId());
        if (ref != null && ref.get() == this) state.panels.remove(panelId());
    }
}
