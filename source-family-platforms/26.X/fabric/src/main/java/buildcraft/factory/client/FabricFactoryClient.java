/* Client-only registration for BuildCraft Factory on Fabric 26.3. MPL-2.0. */
package buildcraft.factory.client;

import buildcraft.factory.BCFactoryClientGuis;
import buildcraft.factory.BCFactoryClientRenderers;
import buildcraft.factory.BCFactoryModels;
import buildcraft.factory.BCFactorySprites;
import buildcraft.lib.platform.client.PlatformClientRegistration;

/** Registered only by the Fabric client entrypoint, never on a dedicated server. */
public final class FabricFactoryClient {
    private static boolean bootstrapped;
    private FabricFactoryClient() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;
        BCFactorySprites.init();
        BCFactoryModels.init();
        BCFactoryClientGuis.clientInit(PlatformClientRegistration.screens());
        BCFactoryClientRenderers.register(PlatformClientRegistration.renderers());
    }
}
