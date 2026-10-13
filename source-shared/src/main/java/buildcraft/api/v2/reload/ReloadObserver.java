package buildcraft.api.v2.reload;

/** Callbacks run in registration order. A failure before publication vetoes the reload. */
public interface ReloadObserver<T> {
    default void beforePublish(ReloadChange<T> change) throws Exception { }
    /** An afterPublish failure is reported as a warning and cannot roll back committed data. */
    default void afterPublish(ReloadChange<T> change) throws Exception { }
}
