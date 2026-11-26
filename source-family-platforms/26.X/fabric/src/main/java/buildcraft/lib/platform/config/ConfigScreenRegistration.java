package buildcraft.lib.platform.config;

/** Fabric Loader has no native config screen extension point. The optional Mod Menu
 * entrypoint (BuildCraftModMenu) and the client key mapping open BCCE's own screen. */
public final class ConfigScreenRegistration {
    private ConfigScreenRegistration() {}
    public static void register(Object ignoredLoaderContainer) {
        // Fabric's entrypoint metadata owns screen registration.
    }
}
