package buildcraft.lib.net.cache;

import net.minecraft.client.Minecraft;
public class NetworkedObjectCacheClientHandler {
    public static boolean isSameThread() {
        return Minecraft.getInstance().isSameThread();
    }
}
