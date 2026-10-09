package buildcraft.lib.internal.api.v2.registry;

import buildcraft.api.v2.registry.ApiRegistry;
import buildcraft.api.v2.registry.RegistrationContext;
import buildcraft.api.v2.registry.RegistryEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Small authoritative registry with provenance, aliases and an explicit freeze boundary. */
public final class ApiRegistryImpl<T> implements ApiRegistry<T> {
    private final Map<Identifier, RegistryEntry<T>> entries = new LinkedHashMap<>();
    private final Map<Identifier, Identifier> aliases = new LinkedHashMap<>();
    private boolean frozen;

    public void register(Identifier id, T value) {
        register(id, value, () -> "unknown");
    }

    public void register(Identifier id, T value, RegistrationContext context) {
        ensureMutable();
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(context, "context");
        String owner = Objects.requireNonNull(context.owner(), "context.owner()");
        if (owner.isBlank()) throw new IllegalArgumentException("registration owner must not be blank");
        if (entries.containsKey(id) || aliases.containsKey(id)) {
            throw new IllegalStateException("Duplicate registry id or alias: " + id);
        }
        entries.put(id, new RegistryEntry<>(id, value, owner));
    }

    public void registerAlias(Identifier alias, Identifier canonicalId, RegistrationContext context) {
        ensureMutable();
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(canonicalId, "canonicalId");
        Objects.requireNonNull(context, "context");
        if (alias.equals(canonicalId)) throw new IllegalArgumentException("alias must differ from canonicalId");
        if (entries.containsKey(alias) || aliases.putIfAbsent(alias, canonicalId) != null) {
            throw new IllegalStateException("Duplicate registry id or alias: " + alias);
        }
    }

    public T get(Identifier id) {
        RegistryEntry<T> entry = entries.get(canonicalId(id));
        return entry == null ? null : entry.value();
    }

    public Optional<RegistryEntry<T>> entry(Identifier id) {
        return Optional.ofNullable(entries.get(canonicalId(id)));
    }

    public Identifier canonicalId(Identifier id) {
        Objects.requireNonNull(id, "id");
        Identifier current = id;
        Set<Identifier> visited = new LinkedHashSet<>();
        while (aliases.containsKey(current)) {
            if (!visited.add(current)) throw new IllegalStateException("Registry alias cycle starting at " + id);
            current = aliases.get(current);
        }
        return current;
    }

    public Collection<T> values() {
        ArrayList<T> values = new ArrayList<>(entries.size());
        for (RegistryEntry<T> entry : entries.values()) values.add(entry.value());
        return Collections.unmodifiableList(values);
    }

    public Collection<RegistryEntry<T>> entries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public Map<Identifier, Identifier> aliases() {
        return Collections.unmodifiableMap(aliases);
    }

    public boolean frozen() { return frozen; }

    public void freeze() {
        if (frozen) return;
        for (Identifier alias : aliases.keySet()) {
            Identifier canonical = canonicalId(alias);
            if (!entries.containsKey(canonical)) {
                throw new IllegalStateException("Registry alias " + alias + " targets missing id " + canonical);
            }
        }
        frozen = true;
    }

    private void ensureMutable() {
        if (frozen) throw new IllegalStateException("Registry is frozen");
    }
}
