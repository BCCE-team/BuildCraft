package buildcraft.silicon.item;

import net.minecraft.world.item.Item;

/** Verification-only gameplay bridge; not included in the production JAR. */
public class ItemRedstoneChipset extends Item {
    public ItemRedstoneChipset(Properties properties) { super(properties); }
    @SuppressWarnings("unchecked")
    public <T> T getType() { return null; }
}
