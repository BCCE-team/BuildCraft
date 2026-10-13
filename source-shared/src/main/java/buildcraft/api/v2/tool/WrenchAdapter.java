package buildcraft.api.v2.tool;

import net.minecraft.world.item.ItemStack;

/** Addon wrench recognition and successful-use notification. */
public interface WrenchAdapter {
    boolean recognizes(ItemStack stack);
    default void onUsed(WrenchUseContext context) { }
}
