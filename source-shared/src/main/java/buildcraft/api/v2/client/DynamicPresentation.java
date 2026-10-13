package buildcraft.api.v2.client;

import buildcraft.api.v2.machine.EngineHost;
import buildcraft.api.v2.machine.MachineExecutionContext;
import buildcraft.api.v2.pipe.PipeView;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side visual selector for an API content id. Called only with client views and synchronized snapshots.
 * The implementation may select resource-pack models/textures and normalized animation channels, but never
 * reads server components or the internal BCCE renderer. Missing methods use registered static metadata.
 */
public interface DynamicPresentation {
    /** Additional model assets the selector may return. Declare them before model baking, not during rendering. */
    default Set<ResourceLocation> models() { return Set.of(); }

    default Optional<PresentationFrame> pipe(PipeView view, float partialTick) { return Optional.empty(); }
    default Optional<PresentationFrame> machine(MachineExecutionContext view, float partialTick) { return Optional.empty(); }
    default Optional<PresentationFrame> engine(EngineHost view, float partialTick) { return Optional.empty(); }
}
