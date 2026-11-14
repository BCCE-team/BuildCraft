package buildcraft.lib.platform.capability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/** A typed, stable BCCE capability identity; external Fabric storage remains native. */
public final class BlockCapability<T, C> {
    private static final Map<Identifier, BlockCapability<?, ?>> REGISTRY = new ConcurrentHashMap<>();
    private final Identifier id;
    private final Class<T> type;
    private BlockCapability(Identifier id, Class<T> type) { this.id = id; this.type = type; }
    @SuppressWarnings("unchecked")
    public static <T, C> BlockCapability<T, C> createSided(Identifier id, Class<T> type) {
        BlockCapability<?, ?> previous = REGISTRY.computeIfAbsent(id, key -> new BlockCapability<>(key, type));
        if (previous.type != type) throw new IllegalArgumentException("Conflicting BCCE capability: " + id);
        return (BlockCapability<T, C>) previous;
    }
    public Identifier id() { return id; }
    public Class<T> type() { return type; }
}
