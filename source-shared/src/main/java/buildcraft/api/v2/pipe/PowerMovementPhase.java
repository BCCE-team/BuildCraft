package buildcraft.api.v2.pipe;

public enum PowerMovementPhase {
    /** Downstream demand propagated towards an energy source. */
    REQUEST,
    /** Active extraction from a connected external provider. */
    EXTRACT,
    /** Outgoing energy offered by the native flow, before loss is applied. */
    SEND,
    /** Resource-service transfer between two native pipe buffers. */
    PIPE_TRANSFER,
    /** Incoming energy offered through a public pipe port. */
    RECEIVE
}
