package buildcraft.api.v2.event;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

/**
 * Loader-neutral replacement for BC8's Forge event-bus boundary. Registration is only
 * allowed before FROZEN. DENY is effective only for cancellable event kinds.
 */
public interface WorldEventService {
    void register(ResourceLocation id, int priority, WorldEventListener listener);
    List<WorldEventSubscription> listeners();
    WorldEventDecision dispatch(WorldEventContext event);
}
