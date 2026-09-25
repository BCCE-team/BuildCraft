package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Direction;

/** Context for fluid entering a pipe from a side or an API caller. */
public record FluidIngressContext(PipeExecutionContext pipe, Optional<Direction> input, OperationMode mode) {
    public FluidIngressContext {
        Objects.requireNonNull(pipe, "pipe");
        input = Objects.requireNonNull(input, "input");
        Objects.requireNonNull(mode, "mode");
    }
}
