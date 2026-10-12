package buildcraft.api.v2.machine;

import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import java.util.Objects;
import java.util.function.Function;

/** Typed, schema-versioned state sent to client menus/screens, never a server component instance. */
public record MachineComponentPresentation<C extends MachineComponent, S>(
    PersistentType<S, OpaqueData> codec, Function<? super C, ? extends S> snapshot
) {
    public MachineComponentPresentation {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
