package buildcraft.api.v2.client;

import buildcraft.api.v2.machine.EngineHost;
import buildcraft.api.v2.machine.MachineExecutionContext;
import buildcraft.api.v2.pipe.PipeView;
import java.util.Optional;

/**
 * Client-side visual selector for an API content id. Called only with client views and synchronized snapshots.
 * The implementation may select resource-pack models/textures and normalized animation channels, but never
 * reads server components or the internal BCCE renderer. Missing methods use registered static metadata.
 */
public interface DynamicPresentation {
    default Optional<PresentationFrame> pipe(PipeView view, float partialTick) { return Optional.empty(); }
    default Optional<PresentationFrame> machine(MachineExecutionContext view, float partialTick) { return Optional.empty(); }
    default Optional<PresentationFrame> engine(EngineHost view, float partialTick) { return Optional.empty(); }
}
