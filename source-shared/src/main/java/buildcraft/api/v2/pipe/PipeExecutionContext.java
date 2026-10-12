package buildcraft.api.v2.pipe;

import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;

/** Live pipe environment. Queries never force a neighbouring chunk to load. */
public interface PipeExecutionContext extends PipeMutationContext {
    boolean isServerSide();

    boolean isActive();

    boolean isNeighbourLoaded(Direction side);

    Optional<BlockState> neighbourState(Direction side);

    Optional<PipeView> neighbourPipe(Direction side);

    /** Server-side mutation; only available while the pipe is active. */
    boolean changeColor(Optional<DyeColor> color);

    /** Re-evaluate connections during a subsequent pipe update, not recursively. */
    void refreshConnections();

    /** Send the named attachment's current state through its existing pipe-side packet. */
    void requestAttachmentSync(Direction side);
}
