package buildcraft.lib.internal.api.v2.reload;

/**
 * Reload is deliberately separate from bootstrap ApiLifecycle.
 */
public enum ReloadPhase {
    PREPARE,
    VALIDATE,
    RESOLVE,
    PUBLISH,
    PUBLISHED,
    FAILED
}
