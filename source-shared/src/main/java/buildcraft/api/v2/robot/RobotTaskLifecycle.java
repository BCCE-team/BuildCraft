package buildcraft.api.v2.robot;

/** Optional task callbacks. Each terminal callback is issued at most once per task instance. */
public interface RobotTaskLifecycle {
    enum Phase { QUEUED, ASSIGNED, RUNNING, SUSPENDED, COMPLETED, FAILED, CANCELLED }

    default void onAssigned(RobotTaskContext context) { }
    default void onResumed(RobotTaskContext context) { }
    default void onSuspended(RobotTaskContext context) { }
    default void onCompleted(RobotTaskContext context) { }
    default void onFailed(RobotTaskContext context) { }
    default void onCancelled(RobotTaskContext context) { }
}
