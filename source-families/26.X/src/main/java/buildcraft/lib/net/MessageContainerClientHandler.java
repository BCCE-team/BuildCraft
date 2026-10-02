package buildcraft.lib.net;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
public class MessageContainerClientHandler {
    public static AbstractContainerMenu getClientContainerMenu() {
        return Minecraft.getInstance().player.containerMenu;
    }
}
