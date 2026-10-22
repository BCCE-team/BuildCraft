package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.event.WorldEventContext;
import buildcraft.api.v2.event.WorldEventDecision;
import buildcraft.api.v2.event.WorldEventListener;
import buildcraft.api.v2.event.WorldEventService;
import buildcraft.api.v2.event.WorldEventSubscription;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Stable dispatch ordering; no Forge/NeoForge event-bus dependency. */
public final class WorldEventServiceImpl implements WorldEventService {
    private final Map<ResourceLocation, WorldEventSubscription> registrations = new LinkedHashMap<>();
    private volatile List<WorldEventSubscription> ordered = List.of();
    private volatile boolean frozen;

    @Override public synchronized void register(ResourceLocation id, int priority, WorldEventListener listener) {
        if (frozen) throw new IllegalStateException("World events are frozen");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(listener, "listener");
        if (registrations.containsKey(id)) throw new IllegalStateException("Duplicate world event listener " + id);
        registrations.put(id, new WorldEventSubscription(id, priority, listener));
        ArrayList<WorldEventSubscription> list = new ArrayList<>(registrations.values());
        list.sort(Comparator.comparingInt(WorldEventSubscription::priority).reversed()
            .thenComparing(registration -> registration.id().toString()));
        ordered = List.copyOf(list);
    }

    @Override public List<WorldEventSubscription> listeners() { return ordered; }

    public synchronized void freeze() { frozen = true; }

    @Override public WorldEventDecision dispatch(WorldEventContext event) {
        Objects.requireNonNull(event, "event");
        if (event.level().isClientSide() || event.level().getServer() == null
            || !event.level().getServer().isSameThread() || !event.level().hasChunkAt(event.pos())) {
            return event.kind().cancellable() ? WorldEventDecision.DENY : WorldEventDecision.PASS;
        }
        WorldEventDecision result = WorldEventDecision.PASS;
        for (WorldEventSubscription subscription : ordered) {
            WorldEventDecision next;
            try {
                next = Objects.requireNonNull(subscription.listener().onWorldEvent(event),
                    "World event decision " + subscription.id());
            } catch (RuntimeException ex) {
                // Never allow a broken listener to silently bypass a cancellable protection hook.
                next = event.kind().cancellable() ? WorldEventDecision.DENY : WorldEventDecision.PASS;
            }
            if (!event.kind().cancellable() && next == WorldEventDecision.DENY) next = WorldEventDecision.PASS;
            result = result.merge(next);
            if (result == WorldEventDecision.DENY) return result;
        }
        return result;
    }
}
