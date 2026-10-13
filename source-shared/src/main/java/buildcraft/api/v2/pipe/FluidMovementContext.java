package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidVolume;
import java.util.Objects;
import net.minecraft.core.Direction;

/** A per-face movement offer. The flow owns the fluid; listeners can only reduce the offered amount. */
public record FluidMovementContext(PipeView pipe, FluidMovementPhase phase, Direction side,
                                   FluidVolume fluid, int offeredMb, int centreCapacityMb,
                                   OperationMode mode) {
    public FluidMovementContext {
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(fluid, "fluid");
        Objects.requireNonNull(mode, "mode");
        if (offeredMb < 0 || centreCapacityMb < 0) throw new IllegalArgumentException("negative fluid amount");
    }
}
