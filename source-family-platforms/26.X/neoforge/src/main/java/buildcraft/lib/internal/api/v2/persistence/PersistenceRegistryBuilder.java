package buildcraft.lib.internal.api.v2.persistence;

import buildcraft.api.v2.persistence.PersistentType;

import buildcraft.api.v2.registry.RegistrationContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.Identifier;

/**
 * Builds a frozen registry of persisted extension types and aliases.
 */
public final class PersistenceRegistryBuilder<T, P> {
    private final Map<Identifier, PersistentType<T, P>> types = new LinkedHashMap<>();
    private final Map<Identifier, String> typeOwners = new LinkedHashMap<>();
    private final Map<Identifier, AliasRecord> aliases = new LinkedHashMap<>();
    private PersistenceRegistrySnapshot<T, P> snapshot;

    public PersistenceRegistryBuilder<T, P> register(PersistentType<T, P> type, RegistrationContext context) {
        ensureMutable();
        Objects.requireNonNull(type, "type");
        String owner = checkedOwner(context);
        Identifier id = type.id();
        if (types.containsKey(id)) {
            throw new IllegalStateException(
                "Duplicate persisted type " + id + " from " + owner + "; already registered by " + typeOwners.get(id)
            );
        }
        if (aliases.containsKey(id)) {
            throw new IllegalStateException("Persisted type id conflicts with an existing alias: " + id);
        }
        types.put(id, type);
        typeOwners.put(id, owner);
        for (Identifier alias : type.aliases()) {
            alias(alias, id, context);
        }
        return this;
    }

    public PersistenceRegistryBuilder<T, P> alias(
        Identifier alias,
        Identifier target,
        RegistrationContext context
    ) {
        ensureMutable();
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(target, "target");
        String owner = checkedOwner(context);
        if (alias.equals(target)) {
            throw new IllegalArgumentException("Alias cannot target itself: " + alias);
        }
        if (types.containsKey(alias)) {
            throw new IllegalStateException("Alias conflicts with a persisted type id: " + alias);
        }
        AliasRecord previous = aliases.putIfAbsent(alias, new AliasRecord(target, owner));
        if (previous != null) {
            throw new IllegalStateException(
                "Duplicate persisted alias " + alias + " from " + owner + "; already registered by " + previous.owner
            );
        }
        return this;
    }

    public PersistenceRegistrySnapshot<T, P> freeze() {
        if (snapshot != null) {
            return snapshot;
        }
        Map<Identifier, Identifier> resolvedAliases = new LinkedHashMap<>();
        for (Identifier alias : aliases.keySet()) {
            resolvedAliases.put(alias, resolveAlias(alias));
        }
        snapshot = new PersistenceRegistrySnapshot<>(
            Collections.unmodifiableMap(new LinkedHashMap<>(types)),
            Collections.unmodifiableMap(resolvedAliases)
        );
        return snapshot;
    }

    private Identifier resolveAlias(Identifier start) {
        Set<Identifier> visited = new LinkedHashSet<>();
        Identifier cursor = start;
        while (true) {
            if (!visited.add(cursor)) {
                throw new IllegalStateException("Persisted alias cycle detected: " + visited + " -> " + cursor);
            }
            AliasRecord next = aliases.get(cursor);
            if (next == null) {
                if (!types.containsKey(cursor)) {
                    throw new IllegalStateException("Persisted alias " + start + " resolves to missing type " + cursor);
                }
                return cursor;
            }
            cursor = next.target;
        }
    }

    private void ensureMutable() {
        if (snapshot != null) {
            throw new IllegalStateException("Persistence registry is already frozen");
        }
    }

    private static String checkedOwner(RegistrationContext context) {
        Objects.requireNonNull(context, "context");
        String owner = Objects.requireNonNull(context.owner(), "context.owner()");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("Registration owner must not be blank");
        }
        return owner;
    }

    private static final class AliasRecord {
        private final Identifier target;
        private final String owner;

        private AliasRecord(Identifier target, String owner) {
            this.target = target;
            this.owner = owner;
        }
    }
}
