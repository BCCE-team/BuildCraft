package buildcraft.lib.platform.runtime;

import buildcraft.lib.net.FabricNetworkManager;
import buildcraft.lib.platform.network.PlatformNetworkTransport;
import net.fabricmc.loader.api.FabricLoader;

/** Fabric implementation of the loader runtime boundary used by common BuildCraft code. */
public final class FabricRuntimePlatform implements RuntimePlatform {
    public static final FabricRuntimePlatform INSTANCE = new FabricRuntimePlatform();

    private FabricRuntimePlatform() {
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public PlatformNetworkTransport network() {
        return FabricNetworkManager.transport();
    }
}
