package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import java.util.Objects;
import net.minecraft.core.Direction;

/** MJ amounts are micro-MJ; external energy amounts are in the platform-neutral FE unit. */
public record PowerMovementContext(PipeView pipe, PipeMedium medium, PowerMovementPhase phase,
                                   Direction side, long offered, OperationMode mode) {
    public PowerMovementContext {
        Objects.requireNonNull(pipe, "pipe");
        if (medium != PipeMedium.MJ && medium != PipeMedium.EXTERNAL_ENERGY)
            throw new IllegalArgumentException("Not a power medium: " + medium);
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(mode, "mode");
        if (offered < 0) throw new IllegalArgumentException("negative offered power");
    }
}
