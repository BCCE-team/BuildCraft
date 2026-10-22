package buildcraft.api.v2.pipe;

/** Outcome of a server-authoritative pipe resource transfer. */
public enum PipeResourceStatus {
    MOVED, PARTIAL, SIMULATED, BLOCKED, UNLOADED, INVALID_SOURCE, INVALID_TARGET, SERVER_ONLY, DENIED, FAILED
}
