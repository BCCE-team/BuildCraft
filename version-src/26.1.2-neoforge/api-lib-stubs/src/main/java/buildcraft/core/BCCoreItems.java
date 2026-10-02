package buildcraft.core;

import java.util.function.Supplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Verification-only gameplay bridge; not included in the production JAR. */
public final class BCCoreItems {
    public static final Supplier<Item> FRAGILE_FLUID_SHARD = () -> Items.GLASS_BOTTLE;
    private BCCoreItems() {}
}
