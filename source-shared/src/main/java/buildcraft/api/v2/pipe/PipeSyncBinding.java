package buildcraft.api.v2.pipe;

/** One typed client sync binding owned by a pipe component. */
public interface PipeSyncBinding<T> {
    PipeSyncChannel<T> channel();
    T snapshot();
    void apply(T state);
}
