package buildcraft.api.v2;

/**
 * Ordered process-wide bootstrap phases, not the lifecycle of a particular world or connection.
 * Registration work must complete before the loader moves to FROZEN. Runtime reload is independent.
 * RUNNING does not by itself imply that a world is loaded. STOPPING is reserved for process shutdown.
 */
public enum ApiLifecycle {
    DISCOVERY,
    TYPE_REGISTRATION,
    CONTENT_REGISTRATION,
    FROZEN,
    RUNNING,
    STOPPING
}
