package buildcraft.robotics.ai;

import java.util.Collections;
import java.util.Set;

import buildcraft.lib.internal.area.IZone;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.IStationFilter;
import buildcraft.robotics.statements.ActionStationForbidRobot;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public class AIRobotSearchStation extends AIRobot {
    public DockingStation targetStation;
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
        if (filter == null) {
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
            if (station == null || !station.isInitialized()) continue;
            if (excludedStations.contains(StationKey.of(station))) continue;
            if (station.isTaken() && station.robotIdTaking() != robot.getRobotId()) continue;
            if (zone != null && !zone.contains(new Vec3(station.x(), station.y(), station.z()))) continue;
            if (!filter.matches(station)) continue;
            if (ActionStationForbidRobot.isForbidden(station, robot)) continue;
            double dx = robot.getX() - station.x();
            double dy = robot.getY() - station.y();
            double dz = robot.getZ() - station.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (targetStation == null || distance < bestDistance) {
                targetStation = station;
                bestDistance = distance;
            }
        }
        terminate();
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
