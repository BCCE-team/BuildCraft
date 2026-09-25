package buildcraft.api.v2.pipe;

import buildcraft.api.v2.energy.MjAmount;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Direction;

public record MjRouteContext(PipeView pipe, Direction input, MjAmount amount, Set<Direction> candidates) {
    public MjRouteContext {
        Objects.requireNonNull(pipe, "pipe"); Objects.requireNonNull(input, "input"); Objects.requireNonNull(amount, "amount");
        candidates = Set.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }

    /** Runtime environment for neighbour/world-aware routing. */
    public PipeExecutionContext execution() {
        if (pipe instanceof PipeExecutionContext context) return context;
        throw new IllegalStateException("Pipe runtime does not expose PipeExecutionContext");
    }
}
