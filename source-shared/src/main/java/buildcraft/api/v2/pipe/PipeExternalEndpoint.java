package buildcraft.api.v2.pipe;

import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.api.v2.signal.SignalPort;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** A live, loader-neutral endpoint supplied by another mod at the adjacent block position.
 * Implementations must not load chunks or mutate the world while queried for a connection.
 */
public interface PipeExternalEndpoint {
    /** Permission for the BuildCraft pipe to make a physical connection to this endpoint. */
    default boolean canConnect(PipeView pipe) { return true; }

    /** Additional model extension, measured in blocks. The runtime bounds the returned value. */
    default float extension() { return 0; }

    default Optional<ItemPort> itemPort() { return Optional.empty(); }
    default Optional<FluidPort> fluidPort() { return Optional.empty(); }
    default Optional<MjPort> mjPort() { return Optional.empty(); }
    default Optional<ExternalEnergyPort> externalEnergyPort() { return Optional.empty(); }
    default Optional<? extends SignalPort<?>> signalPort(ResourceLocation channelId) { return Optional.empty(); }
}
