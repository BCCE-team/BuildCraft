package buildcraft.robotics.ai;

import java.util.Collections;
import java.util.Set;

import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.IStationFilter;
import buildcraft.robotics.RobotTrace;
import buildcraft.robotics.statements.ActionStationForbidRobot;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public class AIRobotSearchStation extends AIRobot {
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
            terminate();
            return;
        }
        double bestDistance = Double.MAX_VALUE;
        for (DockingStation station : robot.getRegistry().getStations()) {
            if (station == null) continue;
            if (excludedStations.contains(StationKey.of(station))) continue;
            if (zone != null && !zone.contains(new Vec3(station.x(), station.y(), station.z()))) continue;
            if (ActionStationForbidRobot.isForbidden(station, robot)) continue;
            if (!station.isInitialized()) {
                temporarilyUnavailable = true;
                continue;
            }
            if (!filter.matches(station)) continue;
            if (station.isTaken() && station.robotIdTaking() != robot.getRobotId()) {
                occupiedMatch = true;
                if (RobotTrace.enabled()) RobotTrace.event(robot, "STATION_OCCUPIED", "station=%s", station);
                continue;
            }
            double dx = robot.getX() - station.x();
            double dy = robot.getY() - station.y();
            double dz = robot.getZ() - station.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (targetStation == null || distance < bestDistance) {
                targetStation = station;
                bestDistance = distance;
            }
        }
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
