package buildcraft.api.v2.pipe;

import java.util.Objects;

/** Integer external-energy units transferred without implicit MJ/FE conversion. */
public record PipeFeTransferResult(long requested, long transferred, PipeResourceStatus status) {
    public PipeFeTransferResult {
        if (requested < 0 || transferred < 0 || transferred > requested) throw new IllegalArgumentException("Invalid energy amount");
        Objects.requireNonNull(status, "status");
    }
    public long remainder() { return requested - transferred; }
    public static PipeFeTransferResult none(long amount, PipeResourceStatus status) {
        return new PipeFeTransferResult(amount, 0, status);
    }
}
