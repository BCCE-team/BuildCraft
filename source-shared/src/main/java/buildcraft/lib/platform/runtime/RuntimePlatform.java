package buildcraft.lib.platform.runtime;

import buildcraft.lib.platform.network.PlatformNetworkTransport;

/** Loader-specific services needed during BuildCraft bootstrap and common runtime execution. */
public interface RuntimePlatform {
    /** Returns whether the loader has the given mod/module id loaded. */
    boolean isModLoaded(String modId);

    /** Returns the packet transport for this loader. */
    PlatformNetworkTransport network();
}
