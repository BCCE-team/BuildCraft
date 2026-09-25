package buildcraft.api.v2.pipe;

import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;

/** State binding that persists into an already-created pipe component instance. */
public interface PipeComponentState<C extends PipeComponent, S> {
    PersistentType<S, OpaqueData> persistence();
    S snapshot(C component);
    void apply(C component, S state);
}
