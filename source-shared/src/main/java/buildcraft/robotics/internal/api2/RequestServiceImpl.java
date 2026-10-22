package buildcraft.robotics.internal.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.PermissionVerdict;
import buildcraft.api.v2.permission.WorldOperationContext;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.api.v2.permission.WorldOperationTarget;
import buildcraft.api.v2.request.ManagedRequestProvider;
import buildcraft.api.v2.request.RequestLedger;
import buildcraft.api.v2.request.RequestProvider;
import buildcraft.api.v2.request.RequestService;
import buildcraft.robotics.internal.legacy.robots.DockingStation;
import buildcraft.robotics.internal.legacy.robots.IRobotRegistry;
import buildcraft.robotics.internal.legacy.robots.RobotManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Live request discovery over Requester blocks and robot docking stations. */
public final class RequestServiceImpl implements RequestService {
    @Override
    public Optional<RequestProvider> provider(Level level, BlockPos pos, Direction side) {
        if (level == null || pos == null) return Optional.empty();
        if (!level.hasChunkAt(pos)) return Optional.empty();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof RequestProvider provider) return Optional.of(provider);
        if (RobotManager.registryProvider == null) return Optional.empty();
        DockingStation station = RobotManager.registryProvider.getRegistry(level).getStation(pos, side);
        return station == null ? Optional.empty() : Optional.ofNullable(station.getRequestProvider());
    }


    private static final ResourceLocation DELIVERY_REASON = java.util.Objects.requireNonNull(
        ResourceLocation.tryParse("buildcraft:robotics_request_delivery"));

    @Override
    public Optional<RequestLedger.PromiseView> reserve(Level level, BlockPos origin, BlockPos destination,
                                                         Direction side, ResourceLocation requestId,
                                                         UUID reservationId, int quantity, AutomationActor actor,
                                                         long durationTicks) {
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(reservationId, "reservationId");
        java.util.Objects.requireNonNull(actor, "actor");
        java.util.Objects.requireNonNull(origin, "origin");
        java.util.Objects.requireNonNull(destination, "destination");
        if (level == null || level.isClientSide() || !level.hasChunkAt(destination)) return Optional.empty();
        if (!permitted(level, origin, destination, actor, OperationMode.EXECUTE)) return Optional.empty();
        return managedProvider(level, destination, side).flatMap(provider ->
            provider.requestLedger().reserve(requestId, reservationId, quantity, actor,
                level.getGameTime(), durationTicks));
    }

    @Override
    public boolean cancel(Level level, BlockPos origin, BlockPos destination, Direction side,
                          ResourceLocation requestId, AutomationActor actor, String reason) {
        java.util.Objects.requireNonNull(requestId, "requestId");
        java.util.Objects.requireNonNull(actor, "actor");
        java.util.Objects.requireNonNull(origin, "origin");
        java.util.Objects.requireNonNull(destination, "destination");
        if (level == null || level.isClientSide() || !level.hasChunkAt(destination)) return false;
        if (!permitted(level, origin, destination, actor, OperationMode.EXECUTE)) return false;
        return managedProvider(level, destination, side)
            .map(provider -> provider.requestLedger().cancel(requestId, actor, reason)).orElse(false);
    }

    private static boolean permitted(Level level, BlockPos origin, BlockPos destination,
                                     AutomationActor actor, OperationMode mode) {
        try {
            return permission(level, origin, destination, actor, mode).verdict() != PermissionVerdict.DENY;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static PermissionDecision permission(Level level, BlockPos origin, BlockPos destination,
                                                 AutomationActor actor, OperationMode mode) {
        return BuildCraftApi.service(BuildCraftServices.PERMISSIONS).decide(
            new WorldOperationContext(actor, level, origin, WorldOperationTarget.block(destination),
                WorldOperationKind.INVENTORY_ACCESS, mode, DELIVERY_REASON));
    }

    @Override
    public RequestLedger.DeliveryResult deliver(Level level, BlockPos origin, BlockPos destination,
                                                 Direction side, UUID reservationId, UUID deliveryId,
                                                 ItemStack stack, AutomationActor actor, OperationMode mode) {
        java.util.Objects.requireNonNull(origin, "origin");
        java.util.Objects.requireNonNull(destination, "destination");
        java.util.Objects.requireNonNull(reservationId, "reservationId");
        java.util.Objects.requireNonNull(deliveryId, "deliveryId");
        java.util.Objects.requireNonNull(stack, "stack");
        java.util.Objects.requireNonNull(actor, "actor");
        java.util.Objects.requireNonNull(mode, "mode");
        if (level == null || level.isClientSide() || !level.hasChunkAt(destination)) {
            return RequestLedger.DeliveryResult.failure(RequestLedger.DeliveryStatus.UNAVAILABLE, "unloaded_or_client_world");
        }
        Optional<ManagedRequestProvider> destinationProvider = managedProvider(level, destination, side);
        if (destinationProvider.isEmpty()) {
            return RequestLedger.DeliveryResult.failure(RequestLedger.DeliveryStatus.UNAVAILABLE, "no_managed_requester");
        }
        try {
            PermissionDecision permission = permission(level, origin, destination, actor, mode);
            if (permission.verdict() == PermissionVerdict.DENY) {
                return RequestLedger.DeliveryResult.failure(RequestLedger.DeliveryStatus.DENIED,
                    permission.reason().orElse("inventory_access_denied"));
            }
            ManagedRequestProvider provider = destinationProvider.get();
            provider.requestLedger().advance(level.getGameTime());
            return provider.requestLedger().deliver(provider, reservationId, deliveryId, stack, actor, mode);
        } catch (RuntimeException failure) {
            // Never retry an uncertain insertion automatically or report it as an accepted transfer.
            return RequestLedger.DeliveryResult.failure(RequestLedger.DeliveryStatus.FAILED, "request_delivery_error");
        }
    }

    @Override
    public Collection<? extends RequestProvider> providers(Level level) {
        if (level == null || RobotManager.registryProvider == null) return List.of();
        IRobotRegistry registry = RobotManager.registryProvider.getRegistry(level);
        Set<RequestProvider> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<RequestProvider> result = new ArrayList<>();
        for (DockingStation station : registry.getStations()) {
            RequestProvider provider = station == null ? null : station.getRequestProvider();
            if (provider != null && seen.add(provider)) result.add(provider);
        }
        return List.copyOf(result);
    }
}
