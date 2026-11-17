package buildcraft.lib.platform.config;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 1.19.2/1.20.1 has no built-in complete editor for a registered ForgeConfigSpec.
 *
 * Do not register a debug-only Mod Config extension point here: that would replace
 * the Core configuration UI with an incomplete view of the Core settings.
 * Core Debug options remain available in buildcraftcore-common.toml.
 */
public final class ConfigScreenRegistration {
    private ConfigScreenRegistration() {}

    /** Compatibility no-op for previously generated source variants. */
    @Deprecated
    public static void registerCoreDebug() {
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        // Intentionally does not replace the mod's configuration screen.
    }
}
