package buildcraft.api.v2.pipe;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Item that is about to become a world entity because no pipe route accepted it. */
public record ItemEjectionContext(
    PipeExecutionContext pipe,
    ItemStack stack,
    Optional<Direction> side,
    Direction motion,
    double speedBlocksPerTick
) {
    public ItemEjectionContext {
        Objects.requireNonNull(pipe, "pipe");
        stack = Objects.requireNonNull(stack, "stack").copy();
        side = Objects.requireNonNull(side, "side");
        Objects.requireNonNull(motion, "motion");
        if (!Double.isFinite(speedBlocksPerTick) || speedBlocksPerTick < 0.0) {
            throw new IllegalArgumentException("speedBlocksPerTick must be finite and non-negative");
        }
    }

    @Override public ItemStack stack() { return stack.copy(); }
}
