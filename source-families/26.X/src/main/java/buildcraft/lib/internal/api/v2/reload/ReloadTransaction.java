package buildcraft.lib.internal.api.v2.reload;

import buildcraft.api.v2.reload.DefinitionEntry;
import buildcraft.api.v2.reload.DefinitionProvenance;
import buildcraft.api.v2.reload.DefinitionSnapshot;
import buildcraft.api.v2.reload.ReloadDiagnostic;
import buildcraft.api.v2.reload.ReloadGeneration;
import buildcraft.api.v2.reload.ResolvedDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.Identifier;

/**
 * Prepare -> validate -> resolve -> publish transaction.
 *
 * Nothing becomes visible until publish succeeds. A failed or stale reload
 * therefore leaves the previous snapshot active.
 */
public final class ReloadTransaction<V> {
    private static final Comparator<DefinitionEntry<?>> ENTRY_ORDER = Comparator
        .<DefinitionEntry<?>, Integer>comparing(entry -> entry.provenance().priority())
        .reversed()
        .thenComparing(entry -> entry.provenance().owner())
        .thenComparing(entry -> entry.provenance().source());

    private final ReloadableDefinitionRegistry<V> registry;
    private final DefinitionSnapshot<V> base;
    private final ReloadGeneration generation;
    private final List<DefinitionEntry<V>> staged = new ArrayList<>();
    private final List<ReloadDiagnostic> diagnostics = new ArrayList<>();
    private ReloadPhase phase = ReloadPhase.PREPARE;
    private DefinitionSnapshot<V> candidate;

    ReloadTransaction(
        ReloadableDefinitionRegistry<V> registry,
        DefinitionSnapshot<V> base,
        ReloadGeneration generation
    ) {
        this.registry = registry;
        this.base = base;
        this.generation = generation;
    }

    public ReloadGeneration generation() {
        return generation;
    }

    public ReloadPhase phase() {
        return phase;
    }

    public DefinitionSnapshot<V> baseSnapshot() {
        return base;
    }

    public List<ReloadDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public ReloadTransaction<V> add(Identifier id, V value, DefinitionProvenance provenance) {
        requirePhase(ReloadPhase.PREPARE);
        staged.add(new DefinitionEntry<>(id, value, provenance));
        return this;
    }

    public ReloadTransaction<V> validate(DefinitionValidator<V> validator) {
        Objects.requireNonNull(validator, "validator");
        if (phase == ReloadPhase.PREPARE) {
            phase = ReloadPhase.VALIDATE;
        }
        requirePhase(ReloadPhase.VALIDATE);
        for (DefinitionEntry<V> definition : staged) {
            List<ReloadDiagnostic> result;
            try {
                result = validator.validate(definition);
            } catch (RuntimeException ex) {
                diagnostics.add(ReloadDiagnostic.error(
                    definition.id(),
                    "Validator threw: " + safeMessage(ex)
                ));
                continue;
            }
            if (result != null) {
                for (ReloadDiagnostic diagnostic : result) {
                    diagnostics.add(Objects.requireNonNull(diagnostic, "diagnostic"));
                }
            }
        }
        return this;
    }

    public ReloadTransaction<V> warning(Identifier id, String message) {
        if (phase != ReloadPhase.PREPARE && phase != ReloadPhase.VALIDATE) {
            throw new IllegalStateException("Diagnostics can only be added before resolve");
        }
        diagnostics.add(ReloadDiagnostic.warning(id, message));
        return this;
    }

    public ReloadTransaction<V> error(Identifier id, String message) {
        if (phase != ReloadPhase.PREPARE && phase != ReloadPhase.VALIDATE) {
            throw new IllegalStateException("Diagnostics can only be added before resolve");
        }
        diagnostics.add(ReloadDiagnostic.error(id, message));
        return this;
    }

    public boolean resolve() {
        if (phase == ReloadPhase.PREPARE) {
            phase = ReloadPhase.VALIDATE;
        }
        requirePhase(ReloadPhase.VALIDATE);
        phase = ReloadPhase.RESOLVE;
        if (hasErrors()) {
            phase = ReloadPhase.FAILED;
            return false;
        }

        Map<Identifier, List<DefinitionEntry<V>>> grouped = new LinkedHashMap<>();
        for (DefinitionEntry<V> definition : staged) {
            grouped.computeIfAbsent(definition.id(), ignored -> new ArrayList<>()).add(definition);
        }

        List<Identifier> ids = new ArrayList<>(grouped.keySet());
        ids.sort(Comparator.comparing(Identifier::toString));
        Map<Identifier, ResolvedDefinition<V>> resolved = new LinkedHashMap<>();
        for (Identifier id : ids) {
            List<DefinitionEntry<V>> candidates = grouped.get(id);
            candidates.sort((left, right) -> ENTRY_ORDER.compare(left, right));
            int winningPriority = candidates.get(0).provenance().priority();
            long samePriority = candidates.stream()
                .filter(entry -> entry.provenance().priority() == winningPriority)
                .count();
            if (samePriority > 1) {
                diagnostics.add(ReloadDiagnostic.error(
                    id,
                    "Ambiguous definition override at priority " + winningPriority + ": " + describeOwners(candidates, winningPriority)
                ));
                continue;
            }
            DefinitionEntry<V> winner = candidates.get(0);
            List<DefinitionEntry<V>> overridden = new ArrayList<>(candidates.subList(1, candidates.size()));
            resolved.put(id, new ResolvedDefinition<>(winner, overridden));
        }

        if (hasErrors()) {
            phase = ReloadPhase.FAILED;
            return false;
        }
        candidate = DefinitionSnapshot.of(generation, resolved);
        phase = ReloadPhase.PUBLISH;
        return true;
    }

    public boolean publish() {
        requirePhase(ReloadPhase.PUBLISH);
        if (!registry.publish(base, candidate)) {
            diagnostics.add(ReloadDiagnostic.error(
                null,
                "Reload generation " + generation.id() + " is stale; a newer snapshot was already published"
            ));
            phase = ReloadPhase.FAILED;
            return false;
        }
        phase = ReloadPhase.PUBLISHED;
        return true;
    }

    private boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.level() == ReloadDiagnostic.Level.ERROR);
    }

    private void requirePhase(ReloadPhase expected) {
        if (phase != expected) {
            throw new IllegalStateException("Reload generation " + generation.id() + " is in phase " + phase + ", expected " + expected);
        }
    }

    private static String describeOwners(Collection<? extends DefinitionEntry<?>> definitions, int priority) {
        List<String> owners = new ArrayList<>();
        for (DefinitionEntry<?> definition : definitions) {
            if (definition.provenance().priority() == priority) {
                owners.add(definition.provenance().owner() + "@" + definition.provenance().source());
            }
        }
        owners.sort(String::compareTo);
        return String.join(", ", owners);
    }

    private static String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
