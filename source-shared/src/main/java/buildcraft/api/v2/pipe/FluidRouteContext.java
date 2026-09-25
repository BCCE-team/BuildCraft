package buildcraft.api.v2.pipe;

import buildcraft.api.v2.fluid.FluidVolume;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Direction;

/**
 * Routing context for fluids currently merged in a pipe centre.
 *
 * <p>Unlike item and energy transport, BuildCraft fluid flow may merge the same fluid from several
 * input faces before selecting output faces. The API therefore exposes every active input instead of
 * inventing a single source direction.</p>
 */
public record FluidRouteContext(PipeView pipe, Set<Direction> inputs, FluidVolume volume, Set<Direction> candidates) {
    public FluidRouteContext {
        Objects.requireNonNull(pipe, "pipe");
        inputs = Set.copyOf(Objects.requireNonNull(inputs, "inputs"));
        Objects.requireNonNull(volume, "volume");
        candidates = Set.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }

    /** Runtime environment for neighbour/world-aware routing. */
    public PipeExecutionContext execution() {
        if (pipe instanceof PipeExecutionContext context) return context;
        throw new IllegalStateException("Pipe runtime does not expose PipeExecutionContext");
    }
}
