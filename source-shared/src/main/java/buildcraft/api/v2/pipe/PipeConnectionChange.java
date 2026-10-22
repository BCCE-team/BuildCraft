package buildcraft.api.v2.pipe;

import java.util.Objects;
import net.minecraft.core.Direction;

/** Connection transition on one face, after the new topology has been committed. */
public record PipeConnectionChange(Direction side, PipeEndpointKind before, PipeEndpointKind after) {
    public PipeConnectionChange {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
    }
}
