package buildcraft.api.v2.pipe;

import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.platform.ExternalEnergyPort;

/** A loader-independent runtime flow/behaviour hosted in the standard BCCE pipe block.
 * The component's factory and PersistentType supply create/restore/migration; its sync channels
 * are encoded by the existing pipe component protocol. All callbacks are exception-isolated.
 */
public interface PipeRuntimeComponent extends PipeTickComponent, PipeComponentLifecycle,
    PipeConnectionComponent, PipeActivationComponent {

    @Override default PipeConnectionDecision connection(PipeConnectionContext context) {
        return PipeConnectionDecision.PASS;
    }

    @Override default PipeActivationResult activate(PipeActivationContext context) {
        return PipeActivationResult.PASS;
    }

    /** Called once after the pipe holder ticks its side-mounted attachments, server-side. */
    default void postAttachmentTick(PipeMutationContext context) { }

    /** Called on the server when an entity intersects this pipe block. */
    default void entityCollided(PipeView pipe, Entity entity) { }

    /** Optional ports for nonstandard and combined flow media. */
    default Optional<ItemPipePort> itemPipePort(Direction side) { return Optional.empty(); }
    default Optional<FluidPort> fluidPort(Direction side) { return Optional.empty(); }
    default Optional<MjPort> mjPort(Direction side) { return Optional.empty(); }
    default Optional<ExternalEnergyPort> externalEnergyPort(Direction side) { return Optional.empty(); }
}
