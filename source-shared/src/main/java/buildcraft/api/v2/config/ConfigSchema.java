package buildcraft.api.v2.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Versioned addon-owned configuration schema, independent for each storage scope.
 * Unversioned files are version zero. Step at index n upgrades n to n+1.
 * Each step receives a mutable copy containing only this namespace's relative keys
 * (without the namespace prefix). Preserve keys you do not intend to remove.
 * Steps must be deterministic, side-effect-free outside that map, and must not call
 * the configuration service. They run before definitions are registered and may
 * run separately for every world file. Fresh stores start at the current version.
 */
public record ConfigSchema(String namespace, ConfigDefinition.Scope scope, int version,
        List<Consumer<Map<String, String>>> migrations) {
    public ConfigSchema {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(scope, "scope");
        if (!namespace.matches("[a-z0-9_.-]{1,190}") || ConfigDefinition.isBuiltInNamespace(namespace))
            throw new IllegalArgumentException("Invalid addon schema namespace: " + namespace);
        if (version < 0 || version > 256) throw new IllegalArgumentException("Schema version outside 0..256");
        migrations = List.copyOf(migrations);
        if (migrations.size() != version) throw new IllegalArgumentException("A migration is required for each version step");
    }
}
