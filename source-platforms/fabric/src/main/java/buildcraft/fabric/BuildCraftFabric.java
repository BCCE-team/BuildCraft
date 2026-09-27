package buildcraft.fabric;

import buildcraft.lib.internal.api.v2.platform.PlatformApi2Bootstrap;
import buildcraft.lib.internal.mj.MjApi2PlatformBridge;
import buildcraft.lib.net.FabricNetworkManager;
import buildcraft.lib.net.FabricServerState;
import buildcraft.lib.platform.actor.BCActors;
import buildcraft.lib.platform.runtime.FabricRuntimePlatform;
import buildcraft.lib.platform.runtime.PlatformRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;

/** Fabric 1.20.1 server bootstrap. Gameplay keeps loader-neutral ownership; this class wires services only. */
public final class BuildCraftFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        PlatformRuntime.install(FabricRuntimePlatform.INSTANCE);
        PlatformApi2Bootstrap.install();
        MjApi2PlatformBridge.install();
        FabricNetworkManager.installServerReceivers();

        ServerLifecycleEvents.SERVER_STARTED.register(FabricServerState::started);
        ServerWorldEvents.UNLOAD.register((server, level) -> BCActors.unloadWorld(level));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            BCActors.stopServer();
            FabricServerState.stopped(server);
        });
    }
}
