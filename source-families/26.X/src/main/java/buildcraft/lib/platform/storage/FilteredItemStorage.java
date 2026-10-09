package buildcraft.lib.platform.storage;

import net.minecraft.world.item.ItemStack;

/** Optional per-slot filter metadata used by gates without exposing a loader item handler. */
public interface FilteredItemStorage extends ItemStorage {
    ItemStack getFilter(int slot);
}
