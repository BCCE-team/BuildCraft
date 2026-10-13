package buildcraft.api.v2.world;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.permission.AutomationActor;
import buildcraft.api.v2.permission.PermissionDecision;
import buildcraft.api.v2.permission.WorldOperationKind;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Borrowed, read-only world facade. An empty query means unloaded, inaccessible, or absent.
 * Never loads chunks, never exposes a mutable Level, and never grants mutation authority.
 * Ports returned by this facade re-check the callback lease, server thread, loaded state,
 * and permissions at the moment of use (not just when discovered).
 */
public interface WorldAccessContext {
    BlockPos origin();
    AutomationActor actor();
    boolean isServerSide();
    long gameTime();
    boolean isLoaded(BlockPos pos);
    Optional<BlockState> blockState(BlockPos pos);
    /** Metadata only: never returns a mutable pipe, component or port. */
    Optional<ResourceLocation> pipeTypeId(BlockPos pos);
    Optional<Set<Direction>> pipeConnections(BlockPos pos);
    Optional<ItemPort> itemPort(BlockPos pos, Direction side);
    Optional<FluidPort> fluidPort(BlockPos pos, Direction side);
    Optional<ExternalEnergyPort> energyPort(BlockPos pos, Direction side);
    /** DENY if the destination is unloaded, the lease expired, or execution is off-thread. */
    PermissionDecision permission(BlockPos pos, WorldOperationKind operation, OperationMode mode);
}
