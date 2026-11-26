package buildcraft.lib.platform.events;


public final class PlatformConfigurationEvents {
    private PlatformConfigurationEvents() {}
    public static void configurationLifecycle() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(buildcraft.lib.internal.config.ConfigurationLifecycle::start);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(buildcraft.lib.internal.config.ConfigurationLifecycle::stop);
    }
}
