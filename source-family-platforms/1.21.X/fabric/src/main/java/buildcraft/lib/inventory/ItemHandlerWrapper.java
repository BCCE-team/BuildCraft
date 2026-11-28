package buildcraft.lib.inventory;

import buildcraft.lib.compat.neoforge263.items.IItemHandler;
import buildcraft.lib.platform.storage.StorageAdapters;

/** Native handler entrypoint; all insertion/extraction policy belongs to the shared storage transactor. */
public final class ItemHandlerWrapper extends ItemStorageTransactor {
    private final IItemHandler nativeHandler;
    public ItemHandlerWrapper(IItemHandler handler) {
        super(StorageAdapters.fromNativeItems(handler));
        this.nativeHandler = handler;
    }
    public Object backingIdentity() { return nativeHandler; }
}
