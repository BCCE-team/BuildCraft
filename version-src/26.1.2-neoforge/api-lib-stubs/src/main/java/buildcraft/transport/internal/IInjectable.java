package buildcraft.transport.internal;

import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

/** Verification-only gameplay bridge; not included in the production JAR. */
public interface IInjectable {
    boolean canInjectItems(Direction from);
    ItemStack injectItem(ItemStack stack, boolean doAdd, Direction from, DyeColor color, double speed);
}
