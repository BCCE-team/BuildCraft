package buildcraft.api.v2.pipe;

import buildcraft.api.v2.fluid.FluidAmount;
import buildcraft.api.v2.fluid.FluidVolume;
import java.util.Objects;

/** Requested and delivered mB; the remainder stays in the source pipe. */
public record PipeFluidTransferResult(FluidAmount requested, FluidVolume transferred, PipeResourceStatus status) {
    public PipeFluidTransferResult {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(transferred, "transferred");
        Objects.requireNonNull(status, "status");
        if (transferred.amount().compareTo(requested) > 0) throw new IllegalArgumentException("Transferred more fluid than requested");
    }
    public FluidAmount remainder() { return requested.minus(transferred.amount()); }
    public static PipeFluidTransferResult none(FluidAmount amount, PipeResourceStatus status) {
        return new PipeFluidTransferResult(amount, FluidVolume.empty(), status);
    }
}
