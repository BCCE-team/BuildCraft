package buildcraft.api.v2.pipe;

import java.util.Collection;
import java.util.List;

/** Component that exposes one or more typed API2 sync channels. */
public interface PipeSyncComponent extends PipeComponent {
    default Collection<PipeSyncBinding<?>> syncBindings() {
        return List.of();
    }
}
