package buildcraft.fabric;

import buildcraft.lib.internal.api.v2.platform.PlatformApi2Bootstrap;
import buildcraft.lib.internal.mj.MjApi2PlatformBridge;
import buildcraft.lib.net.FabricNetworkManager;
import buildcraft.lib.net.FabricServerState;
import buildcraft.lib.platform.runtime.FabricRuntimePlatform;
import buildcraft.lib.platform.runtime.PlatformRuntime;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Fabric 1.20.1 server/platform foundation bootstrap. Gameplay modules are enabled in the next parity stage. */
public final class BuildCraftFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        PlatformRuntime.install(FabricRuntimePlatform.INSTANCE);
        PlatformApi2Bootstrap.install();
        MjApi2PlatformBridge.install();
        FabricNetworkManager.installServerReceivers();

        ServerLifecycleEvents.SERVER_STARTED.register(FabricServerState::started);
        ServerLifecycleEvents.SERVER_STOPPED.register(FabricServerState::stopped);
    }
}
