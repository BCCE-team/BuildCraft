package buildcraft.lib.platform.events;

import buildcraft.lib.client.config.ConfigurationKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Loader hooks only: no injected title/pause screen buttons. */
public final class PlatformConfigurationClient {
    private PlatformConfigurationClient() {}
    public static void registerKeys(IEventBus bus) {
        bus.addListener((RegisterKeyMappingsEvent event)->{
//? if >=1.21.9 {
            event.registerCategory(ConfigurationKey.CATEGORY);
//?}
            event.register(ConfigurationKey.OPEN);
        });
    }
    public static void configurationScreen() {
        PlatformClientEvents.tick(BCEvents.Phase.END,event->ConfigurationKey.tick());
    }
}
