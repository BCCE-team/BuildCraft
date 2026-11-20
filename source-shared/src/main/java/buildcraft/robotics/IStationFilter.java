package buildcraft.robotics;

import buildcraft.robotics.internal.legacy.robots.DockingStation;

@FunctionalInterface
public interface IStationFilter {
    boolean matches(DockingStation station);

    /** Only the recharge search uses a soft preference for linked docks. */
    default boolean prefersProcessorLinkedStations() { return false; }
}
