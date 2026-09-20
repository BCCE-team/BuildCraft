package buildcraft.lib.platform.actor;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

/** Server-thread-owned cache. Worlds use identity, owner keys use value equality. */
public final class ActorCache<W, O, A> {
    private final Map<W, Map<O, A>> actors = new IdentityHashMap<>();
    public A get(W world, O owner, BiFunction<W, O, A> factory) {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(owner, "owner");
        return actors.computeIfAbsent(world, ignored -> new HashMap<>())
            .computeIfAbsent(owner, key -> Objects.requireNonNull(factory.apply(world, key), "actor"));
    }
    public void unload(W world) { actors.remove(world); }
    public void clear() { actors.clear(); }
    public int worldCount() { return actors.size(); }
}
