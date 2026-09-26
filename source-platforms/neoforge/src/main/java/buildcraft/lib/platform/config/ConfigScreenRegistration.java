package buildcraft.lib.platform.config;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLEnvironment;

/** Client-gated registration for NeoForge's generated config editor. */
public final class ConfigScreenRegistration {
    private ConfigScreenRegistration() {}

    public static void register(ModContainer container) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Client.register(container);
        }
    }

    /** Kept isolated so dedicated servers never resolve NeoForge client GUI classes. */
    private static final class Client {
        private Client() {}

        private static void register(ModContainer container) {
            container.registerExtensionPoint(
                net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                net.neoforged.neoforge.client.gui.ConfigurationScreen::new
            );
        }
    }
}
