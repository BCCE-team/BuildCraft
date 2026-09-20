package buildcraft.lib.inventory;

import net.minecraftforge.items.IItemHandler;
import buildcraft.lib.platform.storage.StorageAdapters;

/** Native handler entrypoint; all insertion/extraction policy belongs to the shared storage transactor. */
public final class ItemHandlerWrapper extends ItemStorageTransactor {
    public ItemHandlerWrapper(IItemHandler handler) { super(StorageAdapters.fromNativeItems(handler)); }
}
