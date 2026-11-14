package buildcraft.lib.net;

import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;

/** Server lifecycle is installed by the Fabric entrypoint and cleared on stop. */
public final class FabricServerState {
    private static volatile MinecraftServer server;
    private FabricServerState() {}

    public static void started(MinecraftServer current) {
        server = current;
    }

    public static void stopped(MinecraftServer previous) {
        if (server == previous) server = null;
    }

    public static @Nullable MinecraftServer current() {
        return server;
    }
}
