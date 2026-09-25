package buildcraft.api.v2.pipe;

import java.util.Objects;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

public record ItemRouteContext(PipeView pipe, Direction input, ItemStack stack, Set<Direction> candidates) {
    public ItemRouteContext {
        Objects.requireNonNull(pipe, "pipe"); Objects.requireNonNull(input, "input");
        stack = Objects.requireNonNull(stack, "stack").copy(); candidates = Set.copyOf(Objects.requireNonNull(candidates, "candidates"));
    }
    @Override public ItemStack stack() { return stack.copy(); }

    /** Runtime environment for neighbour/world-aware routing. */
    public PipeExecutionContext execution() {
        if (pipe instanceof PipeExecutionContext context) return context;
        throw new IllegalStateException("Pipe runtime does not expose PipeExecutionContext");
    }
}
