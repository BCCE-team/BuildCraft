package buildcraft.api.v2.pipe;

import buildcraft.api.v2.OperationMode;
import java.util.Objects;

/** Loader-neutral MJ network callback context. */
public record MjNetworkContext(PipeExecutionContext pipe, OperationMode mode) {
    public MjNetworkContext {
        Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(mode, "mode");
    }
}
