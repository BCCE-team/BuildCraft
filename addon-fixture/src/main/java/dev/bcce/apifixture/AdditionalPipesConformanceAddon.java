package dev.bcce.apifixture;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.pipe.ItemTransitComponent;
import buildcraft.api.v2.pipe.ItemTransitContext;
import buildcraft.api.v2.pipe.ItemTransitPhase;
import buildcraft.api.v2.pipe.ItemTransitStatus;
import buildcraft.api.v2.pipe.ItemTransitTransfer;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** An independent item teleport pipe; BCCE retains ownership of every travelling stack. */
public final class AdditionalPipesConformanceAddon implements ItemTransitComponent {
    public static final ResourceLocation TYPE = ResourceLocation.tryParse("api_fixture:teleport_pipe");

    private Level destinationWorld;
    private BlockPos destination;
    private Direction enteringFrom;
    private ItemTransitTransfer lastTransfer;
    private int completedTransfers;
    private int receivedEvents;
    private boolean simulate;
    private ItemTransitContext callbackContext;

    public void destination(Level world, BlockPos pos, Direction side) {
        destinationWorld = Objects.requireNonNull(world, "world");
        destination = Objects.requireNonNull(pos, "pos");
        enteringFrom = Objects.requireNonNull(side, "side");
    }

    public void simulate(boolean value) {
        simulate = value;
    }

    @Override
    public ResourceLocation typeId() {
        return TYPE;
    }

    @Override
    public void onTransit(ItemTransitContext context) {
        if (context.phase() == ItemTransitPhase.TRANSFERRED) {
            receivedEvents++;
            return;
        }
        if (context.phase() != ItemTransitPhase.CENTER || destinationWorld == null) return;
        callbackContext = context;
        lastTransfer = context.transfer(destinationWorld, destination, enteringFrom,
            simulate ? OperationMode.SIMULATE : OperationMode.EXECUTE);
        if (!simulate && lastTransfer.succeeded()) completedTransfers++;
    }

    public ItemTransitTransfer lastTransfer() { return lastTransfer; }
    public int completedTransfers() { return completedTransfers; }
    public int receivedEvents() { return receivedEvents; }
    public ItemTransitStatus status() { return lastTransfer == null ? null : lastTransfer.status(); }
    public ItemTransitContext expiredContext() { return callbackContext; }
}
