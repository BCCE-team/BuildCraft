package buildcraft.api.v2.reload;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/** One immutable view including resolved definitions, aliases and disabled entries. */
public final class ScriptedDefinitionSnapshot<T> {
    private final DefinitionSnapshot<T> definitions;
    private final Map<ResourceLocation, ScriptedDefinition> aliases;
    private final Map<ResourceLocation, ScriptedDefinition> disabled;

    public ScriptedDefinitionSnapshot(DefinitionSnapshot<T> definitions,
                                      Map<ResourceLocation, ScriptedDefinition> aliases,
                                      Map<ResourceLocation, ScriptedDefinition> disabled) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.aliases = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(aliases, "aliases")));
        this.disabled = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(disabled, "disabled")));
    }

    public static <T> ScriptedDefinitionSnapshot<T> empty() {
        return new ScriptedDefinitionSnapshot<>(DefinitionSnapshot.empty(), Map.of(), Map.of());
    }

    public ReloadGeneration generation() { return definitions.generation(); }
    public DefinitionSnapshot<T> definitions() { return definitions; }
    public Map<ResourceLocation, ScriptedDefinition> aliases() { return aliases; }
    public Map<ResourceLocation, ScriptedDefinition> disabled() { return disabled; }

    public ResourceLocation canonicalId(ResourceLocation id) {
        ResourceLocation current = Objects.requireNonNull(id, "id");
        Set<ResourceLocation> visited = new LinkedHashSet<>();
        while (aliases.containsKey(current)) {
            if (!visited.add(current)) throw new IllegalStateException("Definition alias cycle at " + id);
            current = aliases.get(current).target();
        }
        return current;
    }

    public Optional<T> get(ResourceLocation id) {
        ResourceLocation canonical = canonicalId(id);
        return disabled.containsKey(canonical) ? Optional.empty() : definitions.get(canonical);
    }
}
