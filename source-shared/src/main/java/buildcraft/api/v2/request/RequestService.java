package buildcraft.api.v2.request;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public interface RequestService {
    Optional<RequestProvider> provider(Level level, BlockPos pos, Direction side);
    Collection<? extends RequestProvider> providers(Level level);

    /** Discover a provider capable of durable reservations, without loading a chunk. */
    default Optional<ManagedRequestProvider> managedProvider(Level level, BlockPos pos, Direction side) {
        return provider(level, pos, side)
            .filter(ManagedRequestProvider.class::isInstance)
            .map(ManagedRequestProvider.class::cast);
    }

    /** Reserve demand at a loaded provider after inventory-access permission checks. */
    default Optional<RequestLedger.PromiseView> reserve(Level level, BlockPos origin, BlockPos destination,
                                                         Direction side, ResourceLocation requestId,
                                                         UUID reservationId, int quantity, AutomationActor actor,
                                                         long durationTicks) {
        return Optional.empty();
    }

    /** Cancel an owned request, releasing all unfulfilled promises. */
    default boolean cancel(Level level, BlockPos origin, BlockPos destination, Direction side,
                           ResourceLocation requestId, AutomationActor actor, String reason) {
        return false;
    }

    /**
     * Insert a reserved shipment into a loaded endpoint. Implementations enforce the
     * actor's inventory-access permission and invoke the provider's ledger exactly once.
     */
    default RequestLedger.DeliveryResult deliver(Level level, BlockPos origin, BlockPos destination,
                                                 Direction side, UUID reservationId, UUID deliveryId,
                                                 ItemStack stack, AutomationActor actor, OperationMode mode) {
        return RequestLedger.DeliveryResult.failure(RequestLedger.DeliveryStatus.UNAVAILABLE, "unsupported");
    }
}
