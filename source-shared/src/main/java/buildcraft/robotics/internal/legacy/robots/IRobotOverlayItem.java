package buildcraft.robotics.internal.legacy.robots;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.world.item.ItemStack;

public interface IRobotOverlayItem {
    boolean isValidRobotOverlay(ItemStack stack);
    void renderRobotOverlay(ItemStack stack, TextureManager textureManager);
}
