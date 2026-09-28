package buildcraft.fabric;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.module.ModuleInfo;
import buildcraft.lib.LegacyLibModule;
import buildcraft.lib.internal.api.v2.platform.PlatformApi2Bootstrap;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.mj.MjApi2PlatformBridge;
import buildcraft.lib.internal.module.FabricModuleBootstrapContext;
import buildcraft.lib.internal.module.LegacyModuleBootstrap;
import buildcraft.lib.net.FabricNetworkManager;
import buildcraft.lib.net.FabricServerState;
import buildcraft.lib.platform.actor.BCActors;
import buildcraft.lib.platform.runtime.FabricRuntimePlatform;
import buildcraft.lib.platform.runtime.PlatformRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;

/** Fabric 1.20.1 server bootstrap. Gameplay keeps loader-neutral ownership; this class only wires loader services. */
public final class BuildCraftFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        PlatformRuntime.install(FabricRuntimePlatform.INSTANCE);
        PlatformApi2Bootstrap.install();
        MjApi2PlatformBridge.install();

        FabricModuleBootstrapContext modules = new FabricModuleBootstrapContext();
        LegacyModuleBootstrap.bootstrap(modules);
        modules.finish();
        FabricNetworkManager.installServerReceivers();

        LegacyLibModule.logStartup();
        var moduleService = BuildCraftApi.service(BuildCraftServices.MODULES);
        BCLog.logger.info("Loaded Modules:");
        for (ModuleInfo module : moduleService.modules()) {
            if (module.loaded()) {
                BCLog.logger.info("  - " + module.id().getPath());
            }
        }
        BCLog.logger.info("Missing Modules:");
        for (ModuleInfo module : moduleService.modules()) {
            if (!module.loaded()) {
                BCLog.logger.info("  - " + module.id().getPath());
            }
        }

        ServerLifecycleEvents.SERVER_STARTED.register(FabricServerState::started);
        ServerWorldEvents.UNLOAD.register((server, level) -> BCActors.unloadWorld(level));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BCActors.stopServer();
            FabricServerState.stopped(server);
        });
    }
}
