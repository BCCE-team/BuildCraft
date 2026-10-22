package buildcraft.lib.internal.api.v2.runtime;

import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.EncodedPayload;
import buildcraft.api.v2.persistence.KnownPayload;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PayloadCopier;
import buildcraft.api.v2.persistence.PayloadResolution;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.persistence.UnknownPayload;
import buildcraft.lib.internal.api.v2.persistence.PersistenceRegistryBuilder;
import buildcraft.lib.internal.api.v2.persistence.PersistenceRegistrySnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/**
 * Server-owned extension instances and client-owned presentation snapshots.
 * Every operation is confined to the thread that created the host. World access
 * and network delivery are delegated to the owning subsystem.
 */
public final class RuntimeExtensions<T, V> {
    public enum Side { SERVER, CLIENT }
    public enum Phase { CREATED, ATTACHED, ACTIVE, SUSPENDED, INVALID }

    public interface Callbacks<T> {
        default void attach(T value) { }
        default void load(T value) { }
        default void activate(T value) { }
        default void tick(T value) { }
        default void suspend(T value) { }
        default void invalidate(T value) { }
        default void save(T value) { }
    }

    /** A codec decodes a complete instance; attach supplies its runtime context. */
    public record Definition<T, V>(
        ResourceLocation id,
        Supplier<? extends T> factory,
        PersistentType<T, OpaqueData> persistence,
        PersistentType<V, OpaqueData> presentation,
        Function<? super T, ? extends V> capture,
        Callbacks<T> callbacks
    ) {
        public Definition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(factory, "factory");
            Objects.requireNonNull(callbacks, "callbacks");
            if (presentation != null) Objects.requireNonNull(capture, "capture");
            if (persistence != null && !id.equals(persistence.id())) {
                throw new IllegalArgumentException("Persistence id differs from runtime type id: " + id);
            }
            if (presentation != null && !id.equals(presentation.id())) {
                throw new IllegalArgumentException("Presentation id differs from runtime type id: " + id);
            }
        }
    }

    /** The owning transport sends this frame to clients; it is not a network packet. */
    public record SyncFrame(long revision, boolean full, List<EncodedPayload<OpaqueData>> data) {
        public SyncFrame {
            if (revision < 0) throw new IllegalArgumentException("Negative revision");
            data = List.copyOf(Objects.requireNonNull(data, "data"));
        }
    }

    private static final int MAX_ENTRIES = 256;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_BYTES = 1024 * 1024;
    private static final PayloadCopier<OpaqueData> COPY = data ->
        new OpaqueData(data.format(), data.bytes());

    private final Side side;
    private final Thread owner = Thread.currentThread();
    private final Consumer<String> diagnostics;
    private final Map<ResourceLocation, Definition<T, V>> definitions = new LinkedHashMap<>();
    private final PersistenceRegistrySnapshot<T, OpaqueData> persistence;
    private final PersistenceRegistrySnapshot<V, OpaqueData> presentation;
    private final Map<ResourceLocation, Entry> active = new LinkedHashMap<>();
    private final Map<ResourceLocation, EncodedPayload<OpaqueData>> unknown = new LinkedHashMap<>();
    private final Map<ResourceLocation, V> clientView = new LinkedHashMap<>();
    private final Set<ResourceLocation> dirty = new LinkedHashSet<>();
    private Phase phase = Phase.CREATED;
    private long revision;

    public RuntimeExtensions(Collection<Definition<T, V>> types, Side side, Consumer<String> diagnostics) {
        this.side = Objects.requireNonNull(side, "side");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        PersistenceRegistryBuilder<T, OpaqueData> saveRegistry = new PersistenceRegistryBuilder<>();
        PersistenceRegistryBuilder<V, OpaqueData> syncRegistry = new PersistenceRegistryBuilder<>();
        for (Definition<T, V> type : Objects.requireNonNull(types, "types")) {
            Objects.requireNonNull(type, "type");
            if (definitions.putIfAbsent(type.id(), type) != null) {
                throw new IllegalArgumentException("Duplicate runtime type: " + type.id());
            }
            if (definitions.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many runtime types");
            if (type.persistence() != null) {
                saveRegistry.register(type.persistence(), () -> "buildcraft");
            }
            if (type.presentation() != null) {
                syncRegistry.register(type.presentation(), () -> "buildcraft");
            }
        }
        persistence = saveRegistry.freeze();
        presentation = syncRegistry.freeze();
    }

    public Phase phase() {
        checkThread();
        return phase;
    }

    public Optional<T> component(ResourceLocation id) {
        server();
        Entry entry = active.get(Objects.requireNonNull(id, "id"));
        return Optional.ofNullable(entry == null ? null : entry.value);
    }

    public Optional<V> view(ResourceLocation id) {
        client();
        return Optional.ofNullable(clientView.get(Objects.requireNonNull(id, "id")));
    }

    /** Creates one instance from the registered factory. */
    public boolean create(ResourceLocation id) {
        server();
        if (phase != Phase.CREATED && phase != Phase.ATTACHED) throw new IllegalStateException("Cannot attach after activation");
        Definition<T, V> definition = definitions.get(Objects.requireNonNull(id, "id"));
        if (definition == null || active.containsKey(id)) return false;
        for (ResourceLocation stored : unknown.keySet()) {
            if (persistence.canonicalId(stored).map(id::equals).orElse(stored.equals(id))) return false;
        }
        T instance;
        try {
            instance = Objects.requireNonNull(definition.factory().get(), "Factory returned null");
        } catch (RuntimeException ex) {
            report(id, "create", ex);
            return false;
        }
        Entry entry = new Entry(definition, instance, null);
        active.put(id, entry);
        try {
            definition.callbacks().attach(instance);
            if (definition.persistence() != null) {
                CodecResult<EncodedPayload<OpaqueData>> initial = persistence.encode(id, instance);
                if (!initial.successful()) throw new IllegalStateException("Cannot encode initial state: " + initial.errors());
                entry.lastSaved = boundedCopy(initial.valueOrThrow());
            }
        } catch (RuntimeException ex) {
            active.remove(id);
            report(id, "attach/save", ex);
            invalidateFailed(definition, instance);
            return false;
        }
        phase = Phase.ATTACHED;
        dirty.add(id);
        return true;
    }

    /** Restores instances before activation, retaining unrecognized envelopes verbatim. */
    public void restore(Collection<EncodedPayload<OpaqueData>> saved) {
        server();
        if (phase != Phase.CREATED) throw new IllegalStateException("Restore requires an empty host");
        Collection<EncodedPayload<OpaqueData>> checked = Objects.requireNonNull(saved, "saved");
        if (checked.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many persisted components");
        requireBoundedTotal(checked);
        Map<ResourceLocation, PayloadResolution<T, OpaqueData>> pending = new LinkedHashMap<>();
        Map<ResourceLocation, EncodedPayload<OpaqueData>> envelopes = new LinkedHashMap<>();
        for (EncodedPayload<OpaqueData> data : checked) {
            EncodedPayload<OpaqueData> copy = boundedCopy(data);
            PayloadResolution<T, OpaqueData> resolved = persistence.decode(copy, COPY);
            ResourceLocation key = persistence.canonicalId(copy.typeId()).orElse(copy.typeId());
            if (pending.putIfAbsent(key, resolved) != null) {
                throw new IllegalArgumentException("Duplicate persisted component: " + key);
            }
            envelopes.put(key, copy);
        }
        for (Map.Entry<ResourceLocation, PayloadResolution<T, OpaqueData>> record : pending.entrySet()) {
            ResourceLocation id = record.getKey();
            EncodedPayload<OpaqueData> original = envelopes.get(id);
            PayloadResolution<T, OpaqueData> result = record.getValue();
            if (result instanceof KnownPayload<T, OpaqueData> known && definitions.containsKey(id)) {
                Definition<T, V> definition = definitions.get(id);
                T instance = known.value();
                active.put(id, new Entry(definition, instance, original));
                try {
                    definition.callbacks().attach(instance);
                    definition.callbacks().load(instance);
                } catch (RuntimeException ex) {
                    active.remove(id);
                    dirty.remove(id);
                    report(id, "attach/load", ex);
                    invalidateFailed(definition, instance);
                    unknown.put(original.typeId(), original);
                    continue;
                }
                dirty.add(id);
            } else {
                if (result instanceof UnknownPayload<T, OpaqueData> unresolved) {
                    diagnostics.accept("Extension " + original.typeId() + " could not be restored: " + unresolved.diagnostics());
                }
                unknown.put(original.typeId(), original);
            }
        }
        phase = Phase.ATTACHED;
    }

    public void activate() {
        server();
        if (phase != Phase.ATTACHED && phase != Phase.SUSPENDED) throw new IllegalStateException("Cannot activate: " + phase);
        for (Entry entry : active.values()) {
            if (!entry.failed) call(entry, "activate", () -> entry.definition.callbacks().activate(entry.value));
        }
        phase = Phase.ACTIVE;
    }

    public void tick() {
        server();
        if (phase != Phase.ACTIVE) throw new IllegalStateException("Cannot tick: " + phase);
        for (Entry entry : active.values()) {
            if (!entry.failed) call(entry, "tick", () -> entry.definition.callbacks().tick(entry.value));
        }
    }

    public void suspend() {
        server();
        if (phase != Phase.ACTIVE) throw new IllegalStateException("Cannot suspend: " + phase);
        for (Entry entry : active.values()) {
            if (!entry.failed) call(entry, "suspend", () -> entry.definition.callbacks().suspend(entry.value));
        }
        phase = Phase.SUSPENDED;
    }

    /** Saving failures never silently remove a component from the saved state. */
    public List<EncodedPayload<OpaqueData>> save() {
        server();
        List<EncodedPayload<OpaqueData>> result = new ArrayList<>(active.size() + unknown.size());
        for (Entry entry : active.values()) {
            if (entry.definition.persistence() == null) continue;
            if (!entry.failed) call(entry, "save", () -> entry.definition.callbacks().save(entry.value));
            EncodedPayload<OpaqueData> encoded = null;
            if (!entry.failed) {
                CodecResult<EncodedPayload<OpaqueData>> saved = persistence.encode(entry.definition.id(), entry.value);
                if (saved.successful()) {
                    try {
                        encoded = boundedCopy(saved.valueOrThrow());
                    } catch (IllegalArgumentException ex) {
                        report(entry.definition.id(), "save", ex);
                    }
                } else {
                    diagnostics.accept("Extension " + entry.definition.id() + " save failed: " + saved.errors());
                }
            }
            if (encoded == null) encoded = entry.lastSaved;
            if (encoded == null) throw new IllegalStateException("Cannot save extension " + entry.definition.id());
            entry.lastSaved = encoded;
            result.add(encoded);
        }
        result.addAll(unknown.values());
        if (result.size() > MAX_ENTRIES) throw new IllegalStateException("Too many persisted components");
        requireBoundedTotal(result);
        return List.copyOf(result);
    }

    public void markDirty(ResourceLocation id) {
        server();
        if (!active.containsKey(id)) throw new IllegalArgumentException("Missing extension: " + id);
        dirty.add(id);
    }

    public SyncFrame fullSync() {
        server();
        // A new observer's full snapshot must not consume pending deltas for
        // existing observers, which still need their changedSync delivery.
        return encodeSync(true, active.keySet());
    }

    public Optional<SyncFrame> changedSync() {
        server();
        if (dirty.isEmpty()) return Optional.empty();
        SyncFrame frame = encodeSync(false, dirty);
        dirty.clear();
        return Optional.of(frame);
    }

    /** Frames are decoded fully before client presentation values are replaced. */
    public boolean applySync(SyncFrame frame) {
        client();
        Objects.requireNonNull(frame, "frame");
        if (frame.revision() <= revision) return false;
        if (frame.data().size() > MAX_ENTRIES) return false;
        try {
            requireBoundedTotal(frame.data());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        Map<ResourceLocation, V> incoming = new LinkedHashMap<>();
        for (EncodedPayload<OpaqueData> element : frame.data()) {
            try {
                PayloadResolution<V, OpaqueData> decoded = presentation.decode(boundedCopy(element), COPY);
                if (decoded instanceof KnownPayload<V, OpaqueData> known) {
                    if (incoming.putIfAbsent(known.canonicalTypeId(), known.value()) != null) return false;
                } else if (presentation.canonicalId(element.typeId()).isPresent()) {
                    diagnostics.accept("Extension " + element.typeId() + " sync could not be decoded");
                    return false;
                }
            } catch (RuntimeException ex) {
                report(element.typeId(), "sync", ex);
                return false;
            }
        }
        if (frame.full()) clientView.clear();
        clientView.putAll(incoming);
        revision = frame.revision();
        return true;
    }

    public void invalidate() {
        checkThread();
        if (phase == Phase.INVALID) return;
        if (side == Side.SERVER) {
            for (Entry entry : active.values()) {
                try {
                    entry.definition.callbacks().invalidate(entry.value);
                } catch (RuntimeException ex) {
                    report(entry.definition.id(), "invalidate", ex);
                }
            }
        }
        active.clear();
        unknown.clear();
        clientView.clear();
        dirty.clear();
        phase = Phase.INVALID;
    }

    private SyncFrame encodeSync(boolean full, Collection<ResourceLocation> ids) {
        List<EncodedPayload<OpaqueData>> values = new ArrayList<>();
        for (ResourceLocation id : ids) {
            Entry entry = active.get(id);
            if (entry == null || entry.failed || entry.definition.presentation() == null) continue;
            V state;
            try {
                state = Objects.requireNonNull(entry.definition.capture().apply(entry.value), "Missing presentation snapshot");
            } catch (RuntimeException ex) {
                report(id, "sync", ex);
                throw new IllegalStateException("Cannot capture extension presentation: " + id, ex);
            }
            CodecResult<EncodedPayload<OpaqueData>> encoded = presentation.encode(id, state);
            if (!encoded.successful()) {
                throw new IllegalStateException("Cannot sync " + id + ": " + encoded.errors());
            }
            values.add(boundedCopy(encoded.valueOrThrow()));
        }
        requireBoundedTotal(values);
        revision = Math.incrementExact(revision);
        return new SyncFrame(revision, full, values);
    }

    private static void requireBoundedTotal(Collection<EncodedPayload<OpaqueData>> entries) {
        long bytes = 0;
        for (EncodedPayload<OpaqueData> entry : entries) {
            bytes += Objects.requireNonNull(entry, "entry").payload().size();
            if (bytes > MAX_TOTAL_BYTES) throw new IllegalArgumentException("Extension payload budget exceeded");
        }
    }

    private EncodedPayload<OpaqueData> boundedCopy(EncodedPayload<OpaqueData> data) {
        Objects.requireNonNull(data, "data");
        if (data.payload().size() > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Extension payload exceeds " + MAX_PAYLOAD_BYTES + " bytes: " + data.typeId());
        }
        return new EncodedPayload<>(data.typeId(), data.schemaVersion(), COPY.copy(data.payload()));
    }

    private void invalidateFailed(Definition<T, V> definition, T value) {
        try {
            definition.callbacks().invalidate(value);
        } catch (RuntimeException ex) {
            report(definition.id(), "invalidate", ex);
        }
    }

    private void call(Entry entry, String operation, Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ex) {
            entry.failed = true;
            report(entry.definition.id(), operation, ex);
        }
    }

    private void report(ResourceLocation id, String operation, RuntimeException ex) {
        diagnostics.accept("Extension " + id + " " + operation + " failed: " + ex);
    }

    private void requireValid() {
        if (phase == Phase.INVALID) throw new IllegalStateException("Extension host is invalidated");
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Extension host accessed outside its owner thread");
    }

    private void server() {
        checkThread();
        requireValid();
        if (side != Side.SERVER) throw new IllegalStateException("Server-only extension operation");
    }

    private void client() {
        checkThread();
        requireValid();
        if (side != Side.CLIENT) throw new IllegalStateException("Client-only extension operation");
    }

    private final class Entry {
        private final Definition<T, V> definition;
        private final T value;
        private EncodedPayload<OpaqueData> lastSaved;
        private boolean failed;

        private Entry(Definition<T, V> definition, T value, EncodedPayload<OpaqueData> lastSaved) {
            this.definition = definition;
            this.value = value;
            this.lastSaved = lastSaved;
        }
    }
}
