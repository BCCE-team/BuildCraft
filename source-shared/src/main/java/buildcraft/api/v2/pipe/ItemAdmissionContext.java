package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/** Read-only admission request, evaluated before inserting into any item pipe. */
public record ItemAdmissionContext(PipeView pipe, Direction input, ItemStack stack, OperationMode mode) {
    public ItemAdmissionContext {
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(input, "input");
        stack = Objects.requireNonNull(stack, "stack").copy();
        Objects.requireNonNull(mode, "mode");
    }

    @Override public ItemStack stack() { return stack.copy(); }
}
