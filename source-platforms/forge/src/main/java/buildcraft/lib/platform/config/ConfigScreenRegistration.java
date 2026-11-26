package buildcraft.lib.platform.config;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Native mod-list entry point only; configuration remains entirely in BCCE. */
public final class ConfigScreenRegistration {
    private ConfigScreenRegistration() {}
    public static void register() {
        if (FMLEnvironment.dist == Dist.CLIENT) Client.register();
    }
    @Deprecated public static void registerCoreDebug() { register(); }
    private static final class Client {
        private static void register() {
            net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(buildcraft.lib.client.config.BuildCraftConfigurationScreen::new));
        }
    }
}
