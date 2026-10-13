package buildcraft.api.v2.pipe;

import net.minecraft.resources.ResourceLocation;
import buildcraft.api.v2.world.WorldAccessContext;
import java.util.Optional;

/** Scoped mutation operations granted while a component callback is executing. */
public interface PipeMutationContext extends PipeView {
    void markChanged();
    void requestSync(ResourceLocation channelId);
    /** Borrowed, read-only server world facade; only present inside an active component tick. */
    default Optional<WorldAccessContext> worldAccess() { return Optional.empty(); }
}
