//? source if >=1.21.11
package buildcraft.lib.compat.mc121111.client.color.item;

import net.minecraft.world.item.ItemStack;

@FunctionalInterface
public interface ItemColor {
    int getColor(ItemStack stack, int tintIndex);
}
