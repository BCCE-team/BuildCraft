package buildcraft.lib.internal.api.v2;

import buildcraft.lib.internal.api.v2.recipe.MachineRecipeRegistration;
import buildcraft.lib.internal.api.v2.recipe.MachineRecipeReloadResult;
import buildcraft.api.v2.recipe.MachineRecipeService;
import buildcraft.api.v2.recipe.RecipeDefinition;
import buildcraft.api.v2.reload.DefinitionProvenance;
import buildcraft.api.v2.reload.DefinitionSnapshot;
import buildcraft.lib.internal.api.v2.reload.ReloadTransaction;
import buildcraft.lib.internal.api.v2.reload.ReloadableDefinitionRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.resources.Identifier;

/** Internal authoritative machine-recipe registry behind API 2 and legacy facades. */
public final class MachineRecipeRegistryImpl implements MachineRecipeService {
    private final ReloadableDefinitionRegistry<RecipeDefinition> published = new ReloadableDefinitionRegistry<>();
    private final Map<Identifier, MachineRecipeRegistration> codeDefinitions = new LinkedHashMap<>();
    private List<MachineRecipeRegistration> dataDefinitions = List.of();

    public DefinitionSnapshot<RecipeDefinition> snapshot() { return published.current(); }

    public synchronized void register(Identifier id, RecipeDefinition definition, DefinitionProvenance provenance) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(provenance, "provenance");
        if (codeDefinitions.containsKey(id)) throw new IllegalStateException("Duplicate code-owned machine recipe id: " + id);
        MachineRecipeRegistration registration = new MachineRecipeRegistration(id, definition, provenance);
        codeDefinitions.put(id, registration);
        MachineRecipeReloadResult result = rebuild(dataDefinitions);
        if (!result.published()) {
            codeDefinitions.remove(id);
            throw new IllegalStateException("Machine recipe " + id + " could not be published: " + result.diagnostics());
        }
    }

    public synchronized MachineRecipeReloadResult reloadData(Collection<MachineRecipeRegistration> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        List<MachineRecipeRegistration> candidate = new ArrayList<>(definitions.size());
        for (MachineRecipeRegistration registration : definitions) candidate.add(Objects.requireNonNull(registration, "registration"));
        MachineRecipeReloadResult result = rebuild(candidate);
        if (result.published()) dataDefinitions = List.copyOf(candidate);
        return result;
    }

    /** Internal compatibility bridge: atomically replace one code-owned recipe id. */
    public synchronized void replaceCode(Identifier id, RecipeDefinition definition, DefinitionProvenance provenance) {
        MachineRecipeRegistration previous = codeDefinitions.put(
            Objects.requireNonNull(id, "id"),
            new MachineRecipeRegistration(id, Objects.requireNonNull(definition, "definition"), Objects.requireNonNull(provenance, "provenance"))
        );
        MachineRecipeReloadResult result = rebuild(dataDefinitions);
        if (!result.published()) {
            if (previous == null) codeDefinitions.remove(id); else codeDefinitions.put(id, previous);
            throw new IllegalStateException("Machine recipe replacement " + id + " could not be published: " + result.diagnostics());
        }
    }

    public synchronized boolean removeCode(Identifier id) {
        MachineRecipeRegistration previous = codeDefinitions.remove(Objects.requireNonNull(id, "id"));
        if (previous == null) return false;
        MachineRecipeReloadResult result = rebuild(dataDefinitions);
        if (!result.published()) {
            codeDefinitions.put(id, previous);
            throw new IllegalStateException("Machine recipe removal " + id + " could not be published: " + result.diagnostics());
        }
        return true;
    }

    public synchronized List<Identifier> removeCodeIf(Predicate<MachineRecipeRegistration> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        Map<Identifier, MachineRecipeRegistration> previous = new LinkedHashMap<>(codeDefinitions);
        List<Identifier> removed = new ArrayList<>();
        codeDefinitions.entrySet().removeIf(entry -> {
            if (!predicate.test(entry.getValue())) return false;
            removed.add(entry.getKey());
            return true;
        });
        if (removed.isEmpty()) return List.of();
        MachineRecipeReloadResult result = rebuild(dataDefinitions);
        if (!result.published()) {
            codeDefinitions.clear();
            codeDefinitions.putAll(previous);
            throw new IllegalStateException("Machine recipe bulk removal could not be published: " + result.diagnostics());
        }
        return List.copyOf(removed);
    }

    private MachineRecipeReloadResult rebuild(Collection<MachineRecipeRegistration> data) {
        ReloadTransaction<RecipeDefinition> transaction = published.beginReload();
        for (MachineRecipeRegistration registration : codeDefinitions.values()) {
            transaction.add(registration.id(), registration.definition(), registration.provenance());
        }
        for (MachineRecipeRegistration registration : data) {
            transaction.add(registration.id(), registration.definition(), registration.provenance());
        }
        boolean resolved = transaction.resolve();
        boolean publishedOk = resolved && transaction.publish();
        return new MachineRecipeReloadResult(publishedOk, transaction.generation(), transaction.diagnostics());
    }
}
