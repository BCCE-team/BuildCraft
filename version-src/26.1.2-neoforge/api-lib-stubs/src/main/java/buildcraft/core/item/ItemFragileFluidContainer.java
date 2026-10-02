package buildcraft.core.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Verification-only gameplay bridge; not included in the production JAR. */
public class ItemFragileFluidContainer extends Item {
    public ItemFragileFluidContainer(Properties properties) { super(properties); }
    @SuppressWarnings("unchecked")
    public static <T> T getFluid(ItemStack stack) { return null; }
    public static void setFluid(ItemStack stack, Object fluid) {}
}
