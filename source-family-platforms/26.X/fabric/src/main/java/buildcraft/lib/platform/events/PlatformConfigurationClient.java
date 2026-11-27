package buildcraft.lib.platform.events;

import buildcraft.lib.client.config.ConfigurationKey;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

public final class PlatformConfigurationClient {
    private PlatformConfigurationClient() {}
    public static void configurationScreen() {
        PlatformClientEvents.login(buildcraft.lib.internal.config.ConfigurationRegistry.INSTANCE::openClientSession);
        KeyMappingHelper.registerKeyMapping(ConfigurationKey.OPEN);
        PlatformClientEvents.tick(BCEvents.Phase.END,event->{
            ConfigurationKey.tick();
            buildcraft.lib.update.client.UpdateNotificationManager.tick();
        });
    }
}
