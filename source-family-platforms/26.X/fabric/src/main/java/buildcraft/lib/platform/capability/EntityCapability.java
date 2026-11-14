package buildcraft.lib.platform.capability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/** BCCE entity automation token. No nonexistent NeoForge EntityCapability on Fabric. */
public final class EntityCapability<T, C> {
    private static final Map<Identifier, EntityCapability<?, ?>> REGISTRY = new ConcurrentHashMap<>();
    private final Identifier id;
    private final Class<T> type;
    private EntityCapability(Identifier id, Class<T> type) { this.id = id; this.type = type; }
    @SuppressWarnings("unchecked")
    public static <T, C> EntityCapability<T, C> createSided(Identifier id, Class<T> type) {
        EntityCapability<?, ?> previous = REGISTRY.computeIfAbsent(id, key -> new EntityCapability<>(key, type));
        if (previous.type != type) throw new IllegalArgumentException("Conflicting BCCE entity capability: " + id);
        return (EntityCapability<T, C>) previous;
    }
    public Identifier id() { return id; }
    public Class<T> type() { return type; }
}
