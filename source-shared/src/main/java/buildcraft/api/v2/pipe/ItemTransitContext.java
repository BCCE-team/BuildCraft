package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Immutable view of an item entering a pipe runtime. */
public record ItemTransitContext(
    PipeExecutionContext pipe,
    Direction input,
    ItemStack stack,
    ItemTransitData transit,
    OperationMode mode
) {
    public ItemTransitContext {
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(input, "input");
        stack = Objects.requireNonNull(stack, "stack").copy();
        Objects.requireNonNull(transit, "transit");
        Objects.requireNonNull(mode, "mode");
    }

    @Override public ItemStack stack() { return stack.copy(); }
}
