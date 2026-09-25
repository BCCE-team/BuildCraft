package buildcraft.api.v2.pipe;

import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import java.util.Optional;

/** Allows a component to expose additional ports independently from the base pipe medium. */
public interface PipePortProviderComponent extends PipeComponent {
    default Optional<ItemPort> itemPort(PipePortContext context) { return Optional.empty(); }
    default Optional<FluidPort> fluidPort(PipePortContext context) { return Optional.empty(); }
    default Optional<MjPort> mjPort(PipePortContext context) { return Optional.empty(); }
    default Optional<ExternalEnergyPort> externalEnergyPort(PipePortContext context) { return Optional.empty(); }
}
