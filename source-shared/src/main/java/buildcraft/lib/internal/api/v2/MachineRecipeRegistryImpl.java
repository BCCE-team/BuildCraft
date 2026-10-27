package buildcraft.lib.internal.api.v2;

import buildcraft.lib.internal.api.v2.recipe.MachineRecipeRegistration;
import buildcraft.lib.internal.api.v2.recipe.MachineRecipeReloadResult;
import buildcraft.api.v2.recipe.MachineRecipeService;
import buildcraft.api.v2.recipe.RecipeDefinition;
import buildcraft.lib.recipe.RecipeBlacklist;
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
import net.minecraft.resources.ResourceLocation;

/** Internal authoritative machine-recipe registry behind API 2 and legacy facades. */
public final class MachineRecipeRegistryImpl implements MachineRecipeService {
    private final ReloadableDefinitionRegistry<RecipeDefinition> published = new ReloadableDefinitionRegistry<>();
    private final Map<ResourceLocation, MachineRecipeRegistration> codeDefinitions = new LinkedHashMap<>();
    private List<MachineRecipeRegistration> dataDefinitions = List.of();

    @Override
    /** Public service queries and addon lookups never expose blacklisted recipes.
     * The internal published snapshot retains all definitions for reload diagnostics. */
    public DefinitionSnapshot<RecipeDefinition> snapshot() {
        DefinitionSnapshot<RecipeDefinition> all = published.current();
        RecipeBlacklist.Snapshot policy = RecipeBlacklist.snapshot();
        if (policy.denied().isEmpty()) return all;
        var visible = new LinkedHashMap<>(all.asMap());
        visible.entrySet().removeIf(entry -> RecipeBlacklist.isDenied(
            RecipeBlacklist.machineFor(entry.getValue().value().kind()), entry.getKey()));
        return DefinitionSnapshot.of(all.generation(), visible);
    }

    @Override
    public synchronized void register(ResourceLocation id, RecipeDefinition definition, DefinitionProvenance provenance) {
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
    public synchronized void replaceCode(ResourceLocation id, RecipeDefinition definition, DefinitionProvenance provenance) {
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

    public synchronized boolean removeCode(ResourceLocation id) {
        MachineRecipeRegistration previous = codeDefinitions.remove(Objects.requireNonNull(id, "id"));
        if (previous == null) return false;
        MachineRecipeReloadResult result = rebuild(dataDefinitions);
        if (!result.published()) {
            codeDefinitions.put(id, previous);
            throw new IllegalStateException("Machine recipe removal " + id + " could not be published: " + result.diagnostics());
        }
        return true;
    }

    public synchronized List<ResourceLocation> removeCodeIf(Predicate<MachineRecipeRegistration> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        Map<ResourceLocation, MachineRecipeRegistration> previous = new LinkedHashMap<>(codeDefinitions);
        List<ResourceLocation> removed = new ArrayList<>();
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
        // Duplication is reported, not silently ignored or treated as a blacklist.
        for (MachineRecipeRegistration definition : data) {
            MachineRecipeRegistration existing = codeDefinitions.get(definition.id());
            if (existing != null) {
                transaction.warning(definition.id(), "Duplicate recipe ID in Java (" + existing.provenance().source()
                    + ") and datapack (" + definition.provenance().source() + "); datapack wins by priority");
            }
        }
        boolean resolved = transaction.resolve();
        boolean publishedOk = resolved && transaction.publish();
        return new MachineRecipeReloadResult(publishedOk, transaction.generation(), transaction.diagnostics());
    }
}
