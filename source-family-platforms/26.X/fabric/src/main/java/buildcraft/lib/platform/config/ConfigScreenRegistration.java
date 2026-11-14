package buildcraft.lib.platform.config;

/**
 * Fabric has no loader-provided NeoForge-style config screen extension point.
 * BCCE configuration still loads and reloads through PlatformConfig; a client
 * screen must be wired explicitly by the Fabric UI integration (e.g. Mod Menu).
 */
public final class ConfigScreenRegistration {
    private ConfigScreenRegistration() {}

    /** Keeps config registration explicit without fabricating an unusable UI. */
    public static void register(Object ignoredLoaderContainer) {
        // No registration side effect: native Fabric does not supply this API.
    }
}
