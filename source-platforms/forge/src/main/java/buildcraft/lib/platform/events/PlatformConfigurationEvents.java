package buildcraft.lib.platform.events;

import net.minecraftforge.common.MinecraftForge;

public final class PlatformConfigurationEvents {
    private PlatformConfigurationEvents() {}
    public static void configurationLifecycle() {
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerAboutToStartEvent event) -> buildcraft.lib.internal.config.ConfigurationLifecycle.start(event.getServer()));
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent event) -> buildcraft.lib.internal.config.ConfigurationLifecycle.stop(event.getServer()));
    }
}
