package buildcraft.lib.platform.storage;
import net.minecraft.world.item.ItemStack;

/** Optional direct mutation for restoration; never inferred from ordinary insert/extract support. */
public interface MutableItemStorage extends ItemStorage {
    void setStackInSlot(int slot, ItemStack stack);
}
