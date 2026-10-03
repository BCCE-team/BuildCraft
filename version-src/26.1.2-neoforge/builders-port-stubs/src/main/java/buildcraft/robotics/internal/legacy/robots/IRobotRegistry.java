package buildcraft.robotics.internal.legacy.robots;

/** Compile-only resource-reservation surface supplied by the future robotics port. */
public interface IRobotRegistry {
    boolean isTaken(ResourceId resource);

    boolean take(ResourceId resource, EntityRobotBase robot);

    void release(ResourceId resource);
}
