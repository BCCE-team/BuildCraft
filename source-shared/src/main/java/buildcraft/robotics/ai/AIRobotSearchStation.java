package buildcraft.robotics.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.IStationFilter;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.scheduler.ChargingDockQueue;
import buildcraft.robotics.statements.ActionStationForbidRobot;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class AIRobotSearchStation extends AIRobot {
    /** The world key is weak; the value keeps neither entities nor the world alive. */
    private static final Map<Level, ChargingDockQueue<StationKey>> CHARGING_WAITERS = new WeakHashMap<>();

    /** 1.19.2 exposes Entity.level as a field; newer Minecraft versions use level(). */
    private static Level robotLevel(EntityRobotBase robot) {
        //? if <1.20 {
        /*?
        return robot.level;
        ?*/
        //?} else {
        return robot.level();
        //?}
    }

    private static ChargingDockQueue<StationKey> queueFor(EntityRobotBase robot) {
        return CHARGING_WAITERS.computeIfAbsent(robotLevel(robot), ignored -> new ChargingDockQueue<>());
    }

    /** Do not keep a phantom waiting robot after a manual command, unload, or AI abort. */
    public static void cancelChargingWait(EntityRobotBase robot) {
        if (robot == null || robotLevel(robot) == null) return;
        ChargingDockQueue<StationKey> queue = CHARGING_WAITERS.get(robotLevel(robot));
        if (queue != null) queue.cancel(robot.getRobotId());
    }
    public DockingStation targetStation;
    /** An eligible station exists, but is currently reserved by another robot. */
    private boolean occupiedMatch;
    /** A station in the requested area has not finished initializing yet. */
    private boolean temporarilyUnavailable;
    private IStationFilter filter;
    private IZone zone;
    private Set<StationKey> excludedStations = Collections.emptySet();

    public AIRobotSearchStation(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotSearchStation(EntityRobotBase robot, IStationFilter filter, IZone zone) {
        this(robot, filter, zone, Collections.emptySet());
    }

    public AIRobotSearchStation(EntityRobotBase robot, IStationFilter filter, IZone zone, Set<StationKey> excludedStations) {
        this(robot);
        this.filter = filter;
        this.zone = zone;
        this.excludedStations = excludedStations == null ? Collections.emptySet() : excludedStations;
    }

    @Override
    public void start() {
        targetStation = null;
        occupiedMatch = false;
        temporarilyUnavailable = false;
        if (filter == null || robot.getRegistry() == null) {
            setSuccess(false);
            terminate();
            return;
        }
        // A station that already holds the robot is still subject to the zone and
        // forbid-robot gate, exactly like every other candidate in this search.
        DockingStation current = robot.getDockingStation();
        if (current != null && current.isInitialized()
                && !excludedStations.contains(StationKey.of(current))
                && (zone == null || zone.contains(new Vec3(current.x(), current.y(), current.z())))
                && !ActionStationForbidRobot.isForbidden(current, robot)
                && filter.matches(current)) {
            targetStation = current;
            if (filter.prefersProcessorLinkedStations()) cancelChargingWait(robot);
            terminate();
            return;
        }
        // First gather eligible stations. Including occupied stations in the
        // request ensures that an already-waiting low-energy robot cannot be
        // overtaken as soon as a dock becomes free.
        List<DockingStation> eligible = new ArrayList<>();
        Set<StationKey> identities = new HashSet<>();
        for (DockingStation station : robot.getRegistry().getStations()) {
            if (station == null) continue;
            StationKey key = StationKey.of(station);
            if (excludedStations.contains(key)) continue;
            if (zone != null && !zone.contains(new Vec3(station.x(), station.y(), station.z()))) continue;
            if (ActionStationForbidRobot.isForbidden(station, robot)) continue;
            if (!station.isInitialized()) {
                temporarilyUnavailable = true;
                continue;
            }
            if (!filter.matches(station)) continue;
            eligible.add(station);
            identities.add(key);
        }

        boolean charging = filter.prefersProcessorLinkedStations();
        // Unregistered robots have no stable ID to arbitrate by. Let their
        // ordinary station-take validation decide instead of colliding on -1.
        ChargingDockQueue<StationKey> queue = charging && robotLevel(robot) != null
                && robot.getRobotId() != EntityRobotBase.NULL_ROBOT_ID ? queueFor(robot) : null;
        long tick = robotLevel(robot) == null ? 0L : robotLevel(robot).getGameTime();
        if (queue != null) {
            int urgency = robot.getEnergy() <= EntityRobotBase.SHUTDOWN_ENERGY ? 2
                    : robot.getEnergy() < EntityRobotBase.SAFETY_ENERGY ? 1 : 0;
            queue.register(robot.getRobotId(), urgency, tick, identities);
        }

        double bestDistance = Double.MAX_VALUE;
        for (DockingStation station : eligible) {
            if (station.isTaken() && station.robotIdTaking() != robot.getRobotId()) {
                occupiedMatch = true;
                if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_OCCUPIED", "station=%s", station);
                continue;
            }
            if (queue != null && station.robotIdTaking() != robot.getRobotId()
                    && !queue.maySelect(robot.getRobotId(), StationKey.of(station), tick)) {
                // Fairness is contention, not a permanently unavailable route.
                occupiedMatch = true;
                if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_CHARGE_QUEUE_YIELD",
                        "station=%s waiting=%d", station, queue.waitingCount(tick));
                continue;
            }
            double dx = robot.getX() - station.x();
            double dy = robot.getY() - station.y();
            double dz = robot.getZ() - station.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (charging && RobotProcessorWorkScheduler.isLinkedChargingStation(robot, station)) {
                distance *= 0.85D;
            }
            if (targetStation == null || distance < bestDistance) {
                targetStation = station;
                bestDistance = distance;
            }
        }
        // The parent immediately starts AIRobotGotoStation, whose station.take()
        // is the final authority. We hold no speculative lease on the dock.
        if (targetStation != null && queue != null) queue.cancel(robot.getRobotId());
        if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_SEARCH",
                "selected=%s occupiedMatch=%s uninitialized=%s excluded=%d",
                targetStation, occupiedMatch, temporarilyUnavailable, excludedStations.size());
        terminate();
    }

    /** Distinguishes temporary contention from the genuine absence of a matching station. */
    public boolean shouldRetry() {
        return occupiedMatch || temporarilyUnavailable;
    }

    public boolean hasOccupiedMatch() {
        return occupiedMatch;
    }

    @Override
    public boolean success() {
        return targetStation != null;
    }

    public record StationKey(int x, int y, int z, Direction side) {
        public static StationKey of(DockingStation station) {
            return new StationKey(station.x(), station.y(), station.z(), station.side());
        }
    }
}
