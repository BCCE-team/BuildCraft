package buildcraft.api.v2.request;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemTransferResult;
import java.util.Collection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * A requester whose promises and deliveries are owned by its own persisted ledger.
 * The host block entity must save/load the ledger alongside its inventory and call
 * {@link RequestLedger#advance(long)} with the authoritative world game time.
 * Existing delivery robots use the inherited {@link #requests()} and {@link #offer}
 * methods. For discovery by classic robots each matcher must supply at least one
 * matching ItemStack through ItemMatcher.examples(). Managed deliveries use
 * {@link RequestService#deliver} instead.
 */
public interface ManagedRequestProvider extends RequestProvider {
    RequestLedger requestLedger();

    /** The server world's current game time, including after a chunk reload. */
    long requestTime();

    /**
     * Insert into the actual inventory or transport endpoint. This method must respect
     * SIMULATE, return the real accepted count, and not update the request ledger itself.
     */
    ItemTransferResult insertRequested(ResourceLocation requestId, ItemStack offered, OperationMode mode);

    @Override
    default Collection<ItemRequest> requests() {
        requestLedger().advance(requestTime());
        return requestLedger().requests();
    }

    @Override
    default ItemTransferResult offer(ResourceLocation requestId, ItemStack offered, OperationMode mode) {
        requestLedger().advance(requestTime());
        return requestLedger().offerUnreserved(this, requestId, offered, mode);
    }
}
