package buildcraft.api.v2.pipe;

import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/** Runtime context exposed while a pipe component is executing. */
public interface PipeExecutionContext extends PipeMutationContext {
    Level level();
    PipeNeighbourView neighbour(Direction side);
}
