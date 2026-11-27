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
import java.util.List;
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
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(value, "value");
        String owner = Objects.requireNonNull(Objects.requireNonNull(context, "context").owner(), "context.owner()");
        registerOwned(id, value, owner);
    }

    private synchronized void registerOwned(Identifier id, T value, String owner) {
        ensureMutable();
        checkNamespace(id, owner);
        if (entries.containsKey(id) || aliases.containsKey(id)) {
            throw new IllegalStateException("Duplicate registry id or alias: " + id);
        }
        entries.put(id, new RegistryEntry<>(id, value, owner));
    }

    public void registerAlias(Identifier alias, Identifier canonicalId, RegistrationContext context) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(canonicalId, "canonicalId");
        String owner = Objects.requireNonNull(Objects.requireNonNull(context, "context").owner(), "context.owner()");
        registerAliasOwned(alias, canonicalId, owner);
    }

    private synchronized void registerAliasOwned(Identifier alias, Identifier canonicalId, String owner) {
        ensureMutable();
        checkNamespace(alias, owner);
        if (alias.equals(canonicalId)) throw new IllegalArgumentException("alias must differ from canonicalId");
        if (entries.containsKey(alias) || aliases.containsKey(alias)) {
            throw new IllegalStateException("Duplicate registry id or alias: " + alias);
        }
        // Validate before mutating: a rejected cycle must not poison the registry.
        Identifier current = canonicalId;
        Set<Identifier> visited = new LinkedHashSet<>();
        while (aliases.containsKey(current)) {
            if (!visited.add(current) || current.equals(alias)) {
                throw new IllegalArgumentException("Registry alias cycle involving " + alias);
            }
            current = aliases.get(current);
        }
        if (current.equals(alias)) throw new IllegalArgumentException("Registry alias cycle involving " + alias);
        aliases.put(alias, canonicalId);
    }

    public synchronized T get(Identifier id) {
        RegistryEntry<T> entry = entries.get(canonicalId(id));
        return entry == null ? null : entry.value();
    }

    public synchronized Optional<RegistryEntry<T>> entry(Identifier id) {
        return Optional.ofNullable(entries.get(canonicalId(id)));
    }

    public synchronized Identifier canonicalId(Identifier id) {
        Objects.requireNonNull(id, "id");
        Identifier current = id;
        Set<Identifier> visited = new LinkedHashSet<>();
        while (aliases.containsKey(current)) {
            if (!visited.add(current)) throw new IllegalStateException("Registry alias cycle starting at " + id);
            current = aliases.get(current);
        }
        return current;
    }

    public synchronized Collection<T> values() {
        ArrayList<T> values = new ArrayList<>(entries.size());
        for (RegistryEntry<T> entry : entries.values()) values.add(entry.value());
        return Collections.unmodifiableList(values);
    }

    public synchronized Collection<RegistryEntry<T>> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized Map<Identifier, Identifier> aliases() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
    }

    public synchronized boolean frozen() { return frozen; }

    public synchronized void validateFreeze() {
        if (frozen) return;
        for (Identifier alias : aliases.keySet()) {
            Identifier canonical = canonicalId(alias);
            if (!entries.containsKey(canonical)) {
                throw new IllegalStateException("Registry alias " + alias + " targets missing id " + canonical);
            }
        }
    }

    public synchronized void freeze() {
        if (frozen) return;
        validateFreeze();
        frozen = true;
    }

    /** Preserve legacy no-context registration; explicit addon owners must match their namespace. */
    private static void checkNamespace(Identifier id, String owner) {
        if (owner.isBlank()) throw new IllegalArgumentException("registration owner must not be blank");
        String namespace = id.getNamespace();
        if (!namespace.equals(owner) && !"unknown".equals(owner)
            && !("buildcraft".equals(owner) && namespace.startsWith("buildcraft"))) {
            throw new IllegalArgumentException("Owner " + owner + " cannot register identifier " + id);
        }
    }

    private void ensureMutable() {
        if (frozen) throw new IllegalStateException("Registry is frozen");
    }
}
