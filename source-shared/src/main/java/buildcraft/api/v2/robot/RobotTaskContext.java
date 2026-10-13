package buildcraft.api.v2.robot;

import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.request.RequestLedger;
import java.util.Objects;
import net.minecraft.world.level.Level;

public record RobotTaskContext(Level level, RobotHandle robot, AutomationActor actor) {
    public RobotTaskContext {
        Objects.requireNonNull(level, "level"); Objects.requireNonNull(robot, "robot"); Objects.requireNonNull(actor, "actor");
    }

    /** Acquires a robotics resource; leases are released when the executing task ends. */
    public Optional<RobotResourceLease> acquire(RobotResourceRequest request) {
        Objects.requireNonNull(request, "request");
        return BuildCraftApi.service(BuildCraftServices.ROBOTS).acquire(level, robot.id(), request);
    }

    /** Reserve a managed logistics request for the executing robot's owner. */
    public Optional<RequestLedger.PromiseView> reserveRequest(BlockPos origin, BlockPos destination,
            Direction side, ResourceLocation requestId, UUID reservationId, int quantity, long durationTicks) {
        return BuildCraftApi.service(BuildCraftServices.REQUESTS).reserve(level, origin, destination,
                side, requestId, reservationId, quantity, actor, durationTicks);
    }

    /** Cancel a request using the same identity and permissions as the robot's automation actor. */
    public boolean cancelRequest(BlockPos origin, BlockPos destination, Direction side,
            ResourceLocation requestId, String reason) {
        return BuildCraftApi.service(BuildCraftServices.REQUESTS).cancel(level, origin, destination,
                side, requestId, actor, reason);
    }

    /** Deliver a promised shipment without bypassing existing request-ledger duplicate suppression. */
    public RequestLedger.DeliveryResult deliverRequest(BlockPos origin, BlockPos destination,
            Direction side, UUID reservationId, UUID deliveryId, ItemStack stack, OperationMode mode) {
        return BuildCraftApi.service(BuildCraftServices.REQUESTS).deliver(level, origin, destination,
                side, reservationId, deliveryId, stack, actor, mode);
    }
}
