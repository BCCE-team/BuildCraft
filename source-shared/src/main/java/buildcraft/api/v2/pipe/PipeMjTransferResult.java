package buildcraft.api.v2.pipe;

import buildcraft.api.v2.energy.MjAmount;
import java.util.Objects;

/** MJ is measured in micro-MJ; no unit conversion occurs during pipe-to-pipe transfer. */
public record PipeMjTransferResult(MjAmount requested, MjAmount transferred, PipeResourceStatus status) {
    public PipeMjTransferResult {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(transferred, "transferred");
        Objects.requireNonNull(status, "status");
        if (transferred.compareTo(requested) > 0) throw new IllegalArgumentException("Transferred more MJ than requested");
    }
    public MjAmount remainder() { return requested.minus(transferred); }
    public static PipeMjTransferResult none(MjAmount amount, PipeResourceStatus status) {
        return new PipeMjTransferResult(amount, MjAmount.ZERO, status);
    }
}
