package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.world.WorldProperty;
import buildcraft.api.v2.world.WorldPropertyService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;

public final class WorldPropertyServiceImpl implements WorldPropertyService {
    private final Map<Identifier, WorldProperty> properties = new LinkedHashMap<>();
    private volatile Map<Identifier, WorldProperty> snapshot = Map.of();

    public synchronized void register(Identifier id, WorldProperty property) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(property, "property");
        if (properties.containsKey(id)) throw new IllegalStateException("Duplicate world property id: " + id);
        properties.put(id, property);
        snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public Optional<WorldProperty> get(Identifier id) { return Optional.ofNullable(snapshot.get(id)); }
    public Map<Identifier, WorldProperty> properties() { return snapshot; }
}
