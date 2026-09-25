package buildcraft.api.v2.pipe;

import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Loader-neutral view of one neighbouring endpoint around a pipe. */
public interface PipeNeighbourView {
    BlockPos position();
    PipeEndpointKind kind();
    Optional<PipeView> pipe();
    Optional<ItemPort> itemPort();
    Optional<FluidPort> fluidPort();
    Optional<MjPort> mjPort();
    Optional<ExternalEnergyPort> externalEnergyPort();
}
