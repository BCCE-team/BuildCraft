package buildcraft.lib.internal.api.v2.reload;

import buildcraft.api.v2.registry.RegistrationContext;
import buildcraft.api.v2.reload.DefinitionDecoder;
import buildcraft.api.v2.reload.DefinitionEntry;
import buildcraft.api.v2.reload.DefinitionReloadService;
import buildcraft.api.v2.reload.DefinitionSnapshot;
import buildcraft.api.v2.reload.ReloadChange;
import buildcraft.api.v2.reload.ReloadContributor;
import buildcraft.api.v2.reload.ReloadDiagnostic;
import buildcraft.api.v2.reload.ReloadGeneration;
import buildcraft.api.v2.reload.ReloadObserver;
import buildcraft.api.v2.reload.ReloadPublishResult;
import buildcraft.api.v2.reload.ResolvedDefinition;
import buildcraft.api.v2.reload.ScriptableDefinitionType;
import buildcraft.api.v2.reload.ScriptedDefinition;
import buildcraft.api.v2.reload.ScriptedDefinitionSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/** Authoritative loader-neutral script definition domains with atomic publication. */
public final class DefinitionReloadServiceImpl implements DefinitionReloadService {
    private final Map<ResourceLocation, Domain<?>> domains = new ConcurrentHashMap<>();
    private volatile boolean frozen;

    @Override
    public synchronized <T> void registerType(ScriptableDefinitionType<T> type, RegistrationContext context) {
        ensureMutable();
        Objects.requireNonNull(type, "type");
        String owner = owner(context);
        if (!type.id().getNamespace().equals(owner)) {
            throw new IllegalArgumentException("Definition domain " + type.id() + " must belong to " + owner);
        }
        if (domains.putIfAbsent(type.id(), new Domain<>(type)) != null) {
            throw new IllegalStateException("Duplicate definition domain: " + type.id());
        }
    }

    @Override
    public synchronized <T> void registerDecoder(ScriptableDefinitionType<T> type,
                                                  ResourceLocation decoderId, DefinitionDecoder<T> decoder,
                                                  RegistrationContext context) {
        ensureMutable();
        Objects.requireNonNull(decoderId, "decoderId");
        Objects.requireNonNull(decoder, "decoder");
        String owner = owner(context);
        if (!decoderId.getNamespace().equals(owner)) {
            throw new IllegalArgumentException("Decoder " + decoderId + " must belong to " + owner);
        }
        Domain<T> domain = require(type);
        synchronized (domain) {
            if (domain.decoders.putIfAbsent(decoderId, decoder) != null) {
                throw new IllegalStateException("Duplicate definition decoder " + decoderId);
            }
        }
    }

    @Override
    public synchronized <T> void registerContributor(ScriptableDefinitionType<T> type,
                                                      ResourceLocation contributorId, ReloadContributor<T> contributor,
                                                      RegistrationContext context) {
        ensureMutable();
        Objects.requireNonNull(contributorId, "contributorId");
        Objects.requireNonNull(contributor, "contributor");
        String owner = owner(context);
        if (!contributorId.getNamespace().equals(owner)) {
            throw new IllegalArgumentException("Reload contributor " + contributorId + " must belong to " + owner);
        }
        Domain<T> domain = require(type);
        synchronized (domain) {
            if (domain.contributors.putIfAbsent(contributorId, contributor) != null) {
                throw new IllegalStateException("Duplicate reload contributor " + contributorId);
            }
        }
    }

    @Override
    public synchronized <T> void observe(ScriptableDefinitionType<T> type, ResourceLocation listenerId,
                                          ReloadObserver<T> observer, RegistrationContext context) {
        ensureMutable();
        Objects.requireNonNull(listenerId, "listenerId");
        Objects.requireNonNull(observer, "observer");
        String owner = owner(context);
        if (!listenerId.getNamespace().equals(owner)) {
            throw new IllegalArgumentException("Reload observer " + listenerId + " must belong to " + owner);
        }
        Domain<T> domain = require(type);
        synchronized (domain) {
            if (domain.observers.putIfAbsent(listenerId, observer) != null) {
                throw new IllegalStateException("Duplicate reload observer: " + listenerId);
            }
        }
    }

    @Override
    public <T> ScriptedDefinitionSnapshot<T> snapshot(ScriptableDefinitionType<T> type) {
        Domain<T> domain = require(type);
        synchronized (domain) { return domain.current; }
    }

    @Override
    public <T> ReloadPublishResult reload(ScriptableDefinitionType<T> type,
                                          Collection<ScriptedDefinition> definitions) {
        if (!frozen) throw new IllegalStateException("Definition reloads require the frozen API lifecycle");
        Domain<T> domain = require(type);
        Objects.requireNonNull(definitions, "definitions");
        synchronized (domain) {
            if (domain.publishing) throw new IllegalStateException("Recursive reload of " + type.id());
            domain.publishing = true;
            try { return reloadLocked(domain, definitions); }
            finally { domain.publishing = false; }
        }
    }

    private static <T> ReloadPublishResult reloadLocked(Domain<T> domain,
                                                          Collection<ScriptedDefinition> source) {
        ReloadGeneration generation = new ReloadGeneration(domain.nextGeneration.getAndIncrement());
        List<ReloadDiagnostic> diagnostics = new ArrayList<>();
        Map<ResourceLocation, List<ScriptedDefinition>> groups = new LinkedHashMap<>();
        for (ScriptedDefinition entry : source) {
            ScriptedDefinition safe = Objects.requireNonNull(entry, "definition");
            groups.computeIfAbsent(safe.id(), ignored -> new ArrayList<>()).add(safe);
        }
        // The same contributions are re-evaluated on every reload, never retained from a prior generation.
        for (Map.Entry<ResourceLocation, ReloadContributor<T>> contributor : domain.contributors.entrySet()) {
            try {
                Collection<ScriptedDefinition> extra = Objects.requireNonNull(
                    contributor.getValue().contribute(generation), "reload contributions");
                for (ScriptedDefinition entry : extra) {
                    ScriptedDefinition safe = Objects.requireNonNull(entry, "contributed definition");
                    groups.computeIfAbsent(safe.id(), ignored -> new ArrayList<>()).add(safe);
                }
            } catch (Exception ex) {
                diagnostics.add(ReloadDiagnostic.error(contributor.getKey(),
                    "Reload contributor failed: " + safeMessage(ex)));
                return new ReloadPublishResult(false, generation, diagnostics);
            }
        }
        Map<ResourceLocation, ResolvedDefinition<T>> resolved = new LinkedHashMap<>();
        Map<ResourceLocation, ScriptedDefinition> aliases = new LinkedHashMap<>();
        Map<ResourceLocation, ScriptedDefinition> disabled = new LinkedHashMap<>();
        List<ResourceLocation> ids = new ArrayList<>(groups.keySet());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (ResourceLocation id : ids) {
            List<ScriptedDefinition> candidates = groups.get(id);
            candidates.sort(Comparator.comparingInt((ScriptedDefinition entry) -> entry.provenance().priority())
                .reversed().thenComparing(entry -> entry.provenance().owner())
                .thenComparing(entry -> entry.provenance().source()));
            ScriptedDefinition winner = candidates.get(0);
            if (candidates.size() > 1 && candidates.get(1).provenance().priority() == winner.provenance().priority()) {
                diagnostics.add(ReloadDiagnostic.error(id, "Ambiguous definition priority "
                    + winner.provenance().priority() + " from " + winner.provenance().owner() + " and "
                    + candidates.get(1).provenance().owner()));
                continue;
            }
            switch (winner.kind()) {
                case DISABLED -> disabled.put(id, winner);
                case ALIAS -> aliases.put(id, winner);
                case VALUE -> {
                    try {
                        T value = Objects.requireNonNull(domain.decode(winner), "decoded definition");
                        if (!domain.type.javaType().isInstance(value)) {
                            throw new IllegalArgumentException("Decoded value has unexpected Java type");
                        }
                        List<DefinitionEntry<T>> overridden = new ArrayList<>();
                        // Record only decoded value overrides; metadata for disabled/alias winners stays separate.
                        for (int i = 1; i < candidates.size(); i++) {
                            ScriptedDefinition previous = candidates.get(i);
                            if (previous.kind() != ScriptedDefinition.Kind.VALUE) continue;
                            try {
                                T replaced = domain.decode(previous);
                                if (replaced != null && domain.type.javaType().isInstance(replaced)) {
                                    overridden.add(new DefinitionEntry<>(previous.id(), replaced, previous.provenance()));
                                }
                            } catch (Exception ignored) {
                                // An overridden lower-priority value cannot invalidate the active definition.
                            }
                        }
                        resolved.put(id, new ResolvedDefinition<>(
                            new DefinitionEntry<>(id, value, winner.provenance()), overridden));
                    } catch (Exception ex) {
                        diagnostics.add(ReloadDiagnostic.error(id, "Definition decoder failed: " + safeMessage(ex)));
                    }
                }
            }
        }
        // Alias references can chain; every chain must terminate at a live value.
        for (ResourceLocation id : aliases.keySet()) {
            Set<ResourceLocation> seen = new LinkedHashSet<>();
            ResourceLocation target = id;
            while (aliases.containsKey(target)) {
                if (!seen.add(target)) {
                    diagnostics.add(ReloadDiagnostic.error(id, "Definition alias cycle at " + target));
                    break;
                }
                target = aliases.get(target).target();
            }
            if (!seen.contains(target) && !resolved.containsKey(target)) {
                diagnostics.add(ReloadDiagnostic.error(id,
                    "Definition alias has no enabled target: " + target));
            }
        }
        if (hasErrors(diagnostics)) return new ReloadPublishResult(false, generation, diagnostics);

        ScriptedDefinitionSnapshot<T> candidate = new ScriptedDefinitionSnapshot<>(
            DefinitionSnapshot.of(generation, resolved), aliases, disabled);
        ReloadChange<T> change = new ReloadChange<>(domain.type.id(), domain.current, candidate);
        for (Map.Entry<ResourceLocation, ReloadObserver<T>> entry : domain.observers.entrySet()) {
            try {
                entry.getValue().beforePublish(change);
            } catch (Exception ex) {
                diagnostics.add(ReloadDiagnostic.error(entry.getKey(), "Before-reload listener failed: " + safeMessage(ex)));
                return new ReloadPublishResult(false, generation, diagnostics);
            }
        }
        domain.current = candidate; // publish exactly once after decoding and every veto check
        for (Map.Entry<ResourceLocation, ReloadObserver<T>> entry : domain.observers.entrySet()) {
            try {
                entry.getValue().afterPublish(change);
            } catch (Exception ex) {
                diagnostics.add(ReloadDiagnostic.warning(entry.getKey(), "After-reload listener failed: " + safeMessage(ex)));
            }
        }
        return new ReloadPublishResult(true, generation, diagnostics);
    }

    private static boolean hasErrors(List<ReloadDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(value -> value.level() == ReloadDiagnostic.Level.ERROR);
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    @SuppressWarnings("unchecked")
    private <T> Domain<T> require(ScriptableDefinitionType<T> type) {
        Objects.requireNonNull(type, "type");
        Domain<?> domain = domains.get(type.id());
        if (domain == null) throw new IllegalArgumentException("Unknown definition domain: " + type.id());
        if (!domain.type.javaType().equals(type.javaType())) {
            throw new IllegalArgumentException("Definition domain Java type mismatch: " + type.id());
        }
        return (Domain<T>) domain;
    }

    private static String owner(RegistrationContext context) {
        String owner = Objects.requireNonNull(Objects.requireNonNull(context, "context").owner(), "owner");
        if (owner.isBlank()) throw new IllegalArgumentException("owner must not be blank");
        return owner;
    }

    private void ensureMutable() {
        if (frozen) throw new IllegalStateException("Reload definition types are frozen");
    }

    public synchronized void freeze() { frozen = true; }
    @Override public boolean frozen() { return frozen; }

    private static final class Domain<T> {
        final ScriptableDefinitionType<T> type;
        final AtomicLong nextGeneration = new AtomicLong(1);
        final Map<ResourceLocation, ReloadObserver<T>> observers = new LinkedHashMap<>();
        final Map<ResourceLocation, ReloadContributor<T>> contributors = new LinkedHashMap<>();
        final Map<ResourceLocation, DefinitionDecoder<T>> decoders = new LinkedHashMap<>();
        ScriptedDefinitionSnapshot<T> current = ScriptedDefinitionSnapshot.empty();
        boolean publishing;
        T decode(ScriptedDefinition definition) throws Exception {
            Object selected = definition.data().get("type");
            if (selected == null) return type.decoder().decode(definition.data());
            if (!(selected instanceof String text)) {
                throw new IllegalArgumentException("Definition type must be a namespaced string");
            }
            ResourceLocation id = ResourceLocation.tryParse(text);
            if (id == null) throw new IllegalArgumentException("Invalid definition type: " + text);
            DefinitionDecoder<T> decoder = decoders.get(id);
            if (decoder == null) throw new IllegalArgumentException("Unknown definition type: " + id);
            return decoder.decode(definition.data());
        }

        Domain(ScriptableDefinitionType<T> type) { this.type = type; }
    }
}
