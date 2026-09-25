package buildcraft.lib.platform.runtime;

import java.util.Objects;

import buildcraft.lib.platform.network.PlatformNetworkTransport;

/**
 * Process-wide loader runtime installed by the BuildCraft library bootstrap.
 *
 * <p>Common gameplay may query this boundary, but must never depend on Forge/NeoForge/Fabric loader classes.</p>
 */
public final class PlatformRuntime {
    private static volatile RuntimePlatform platform;

    private PlatformRuntime() {
    }

    public static synchronized void install(RuntimePlatform value) {
        Objects.requireNonNull(value, "value");
        RuntimePlatform current = platform;
        if (current != null && current != value && current.getClass() != value.getClass()) {
            throw new IllegalStateException(
                "BuildCraft runtime platform already installed as " + current.getClass().getName()
                    + ", cannot replace it with " + value.getClass().getName()
            );
        }
        platform = value;
    }

    public static boolean isInstalled() {
        return platform != null;
    }

    public static RuntimePlatform require() {
        RuntimePlatform current = platform;
        if (current == null) {
            throw new IllegalStateException("BuildCraft runtime platform has not been installed yet");
        }
        return current;
    }

    public static boolean isModLoaded(String modId) {
        return require().isModLoaded(Objects.requireNonNull(modId, "modId"));
    }

    public static PlatformNetworkTransport network() {
        return require().network();
    }
}
