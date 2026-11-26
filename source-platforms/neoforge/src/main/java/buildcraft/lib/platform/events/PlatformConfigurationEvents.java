package buildcraft.lib.platform.events;

import net.neoforged.neoforge.common.NeoForge;

public final class PlatformConfigurationEvents {
    private PlatformConfigurationEvents() {}
    public static void configurationLifecycle() {
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) -> buildcraft.lib.internal.config.ConfigurationLifecycle.start(event.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> buildcraft.lib.internal.config.ConfigurationLifecycle.stop(event.getServer()));
    }
}
