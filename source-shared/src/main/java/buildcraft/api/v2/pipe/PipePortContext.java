package buildcraft.api.v2.pipe;

import java.util.Objects;
import net.minecraft.core.Direction;

/** Sided context for component-provided loader-neutral ports. */
public record PipePortContext(PipeExecutionContext pipe, Direction side) {
    public PipePortContext {
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(side, "side");
    }
}
