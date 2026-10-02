package buildcraft.silicon;

import java.util.Map;
import java.util.HashMap;
import java.util.function.Supplier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Verification-only gameplay bridge; not included in the production JAR. */
public final class BCSiliconItems {
    public static final Supplier<Item> ASSEMBLY_TABLE_ITEM = () -> Items.CRAFTING_TABLE;
    public static final Map<Object, Item> REDSTONE_CHIPSET_ITEMS = new HashMap<>();
    private BCSiliconItems() {}
}
