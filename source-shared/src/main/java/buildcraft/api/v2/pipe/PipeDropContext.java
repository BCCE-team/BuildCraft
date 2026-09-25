package buildcraft.api.v2.pipe;

import java.util.Objects;

/** Context used when component-owned items are collected for a real pipe drop. */
public record PipeDropContext(PipeExecutionContext pipe, int fortune, boolean dropSelf) {
    public PipeDropContext {
        Objects.requireNonNull(pipe, "pipe");
    }
}
