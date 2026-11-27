package buildcraft.robotics.internal.legacy.robots;

/** Home station ownership is exclusive. Temporary charging/cargo reservations are not home links. */
public final class RobotHomeLinks {
    private RobotHomeLinks() { }

    public static void keepOnly(EntityRobotBase robot, DockingStation home) {
        IRobotRegistry registry = robot.getRegistry();
        if (registry == null || home == null || !home.isMainStation()
                || home.robotIdTaking() != robot.getRobotId()) return;
        for (DockingStation station : registry.getStations()) {
            if (station == home || station == null || !station.isMainStation()
                    || station.robotIdTaking() != robot.getRobotId()) continue;
            if (robot.getDockingStation() == station) robot.undock();
            station.unsafeRelease(robot);
            registry.release(station, robot.getRobotId());
        }
        registry.registryMarkDirty();
    }
}
