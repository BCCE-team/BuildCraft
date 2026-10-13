package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.EncodedPayload;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.pipe.PipeComponent;
import buildcraft.api.v2.pipe.PipeComponentLifecycle;
import buildcraft.api.v2.pipe.PipeConnectionChange;
import buildcraft.api.v2.pipe.PipeComponentSync;
import buildcraft.api.v2.pipe.PipeComponentType;
import buildcraft.api.v2.pipe.PipeFlowDefinition;
import buildcraft.api.v2.pipe.PipeBehaviourDefinition;
import buildcraft.api.v2.pipe.PipeRuntimeComponent;
import buildcraft.api.v2.pipe.PipeSyncChannel;
import buildcraft.api.v2.pipe.PipeTickComponent;
import buildcraft.api.v2.pipe.PipeType;
import buildcraft.api.v2.pipe.PipeView;
import buildcraft.api.v2.pipe.PipeMutationContext;
import buildcraft.lib.internal.api.v2.runtime.RuntimeExtensions;
import buildcraft.lib.internal.debug.BCLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;

/** Owns addon component instances, persisted envelopes and typed client sync for a pipe. */
public final class PipeComponentRuntime {
    private static final int STATE_MAGIC = 0x42435043;
    private static final int STATE_VERSION = 1;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_ENTRY_BYTES = 64 * 1024;
    private static final int MAX_STATE_BYTES = 1_200_000;
    private final PipeMutationContext pipe;
    private final boolean client;
    private final RuntimeExtensions<PipeComponent, Object> server;
    private final List<PipeComponent> components;
    private final Map<ResourceLocation, Channel<?>> channels = new LinkedHashMap<>();
    private final Set<ResourceLocation> requested = new LinkedHashSet<>();
    private byte[] unreadableState;
    private long outgoingRevision;
    private long incomingRevision = -1;
    private boolean clientInvalidated;
    private boolean placed;
    private boolean removed;
    private long callbackSerial;
    private long activeCallback;

    public PipeComponentRuntime(PipeMutationContext pipe, PipeType pipeType, boolean client, byte[] persisted) {
        this.pipe = Objects.requireNonNull(pipe, "pipe");
        Objects.requireNonNull(pipeType, "pipeType");
        this.client = client;
        List<EncodedPayload<OpaqueData>> saved = List.of();
        if (!client && persisted != null && persisted.length != 0) {
            try {
                saved = readState(persisted);
            } catch (IOException | RuntimeException ex) {
                unreadableState = persisted.clone();
                report("Cannot decode pipe component state; preserving bytes", ex);
            }
        }
        LinkedHashMap<ResourceLocation, PipeComponentType<?>> types = new LinkedHashMap<>();
        for (ResourceLocation id : pipeType.defaultComponents()) {
            PipeComponentType<?> type = BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).get(id);
            if (type == null) {
                throw new IllegalStateException("Unknown component " + id + " for pipe " + pipeType.id());
            }
            types.put(type.id(), type);
        }
        pipeType.flowTypeId().ifPresent(id -> {
            PipeFlowDefinition<?> definition = BuildCraftApi.registry(BuildCraftRegistries.PIPE_FLOW_TYPES).get(id);
            if (definition == null) throw new IllegalStateException("Unregistered pipe flow: " + id);
            PipeComponentType<?> existing = types.putIfAbsent(
                definition.componentType().id(), definition.componentType());
            if (existing != null && existing != definition.componentType()) {
                throw new IllegalStateException("Conflicting runtime component type for pipe: "
                    + definition.componentType().id());
            }
        });
        pipeType.behaviourTypeId().ifPresent(id -> {
            PipeBehaviourDefinition<?> definition = BuildCraftApi.registry(BuildCraftRegistries.PIPE_BEHAVIOUR_TYPES).get(id);
            if (definition == null) throw new IllegalStateException("Unregistered pipe behaviour: " + id);
            PipeComponentType<?> existing = types.putIfAbsent(
                definition.componentType().id(), definition.componentType());
            if (existing != null && existing != definition.componentType()) {
                throw new IllegalStateException("Conflicting runtime component type for pipe: "
                    + definition.componentType().id());
            }
        });
        if (!client) {
            for (EncodedPayload<OpaqueData> entry : saved) {
                for (PipeComponentType<?> type : BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).values()) {
                    if (entry.typeId().equals(type.id())
                        || type.persistence().map(p -> p.aliases().contains(entry.typeId())).orElse(false)) {
                        types.putIfAbsent(type.id(), type);
                    }
                }
            }
        }
        if (types.size() > MAX_ENTRIES) throw new IllegalArgumentException("Too many pipe components");
        List<PipeComponent> created = new ArrayList<>();
        if (client) {
            server = null;
            for (PipeComponentType<?> type : types.values()) {
                try {
                    PipeComponent instance = Objects.requireNonNull(type.create(pipe), "Null pipe component");
                    if (!type.id().equals(instance.typeId())) throw new IllegalStateException("Incorrect component type id");
                    bindLifecycle(instance);
                    created.add(instance);
                    bindChannels(type, instance);
                } catch (RuntimeException ex) {
                    report("Cannot create client component " + type.id(), ex);
                }
            }
        } else {
            List<RuntimeExtensions.Definition<PipeComponent, Object>> definitions = new ArrayList<>();
            for (PipeComponentType<?> type : types.values()) {
                RuntimeExtensions.Callbacks<PipeComponent> callbacks = new RuntimeExtensions.Callbacks<>() {
                    @Override public void attach(PipeComponent value) {
                        if (!type.id().equals(value.typeId())) throw new IllegalArgumentException("Incorrect component type id");
                        bindLifecycle(value);
                    }
                    @Override public void load(PipeComponent value) {
                        if (value instanceof PipeComponentLifecycle lifecycle) lifecycle.restored();
                    }
                    @Override public void activate(PipeComponent value) {
                        if (value instanceof PipeComponentLifecycle lifecycle) lifecycle.activated();
                    }
                    @Override public void suspend(PipeComponent value) {
                        if (value instanceof PipeComponentLifecycle lifecycle) lifecycle.suspended();
                    }
                    @Override public void tick(PipeComponent value) {
                        if (value instanceof PipeTickComponent ticking) {
                            long previous = activeCallback;
                            activeCallback = ++callbackSerial;
                            try { ticking.tick(pipe); } finally { activeCallback = previous; }
                        }
                    }
                    @Override public void invalidate(PipeComponent value) {
                        if (value instanceof PipeComponentLifecycle lifecycle) lifecycle.invalidated();
                    }
                };
                definitions.add(new RuntimeExtensions.Definition<>(type.id(), () -> type.create(pipe),
                    persistence(type), null, null, callbacks));
            }
            RuntimeExtensions<PipeComponent, Object> runtime = new RuntimeExtensions<>(definitions,
                RuntimeExtensions.Side.SERVER,
                message -> BCLog.logger.warn("{} at {}", message, pipe.position()));
            try {
                runtime.restore(saved);
            } catch (RuntimeException ex) {
                unreadableState = persisted == null ? null : persisted.clone();
                report("Cannot restore pipe component state; preserving bytes", ex);
                runtime = new RuntimeExtensions<>(definitions, RuntimeExtensions.Side.SERVER,
                    message -> BCLog.logger.warn("{} at {}", message, pipe.position()));
                runtime.restore(List.of());
            }
            server = runtime;
            if (unreadableState == null) {
                for (ResourceLocation id : pipeType.defaultComponents()) server.create(id);
                pipeType.flowTypeId().ifPresent(id -> server.create(
                    BuildCraftApi.registry(BuildCraftRegistries.PIPE_FLOW_TYPES).get(id).componentType().id()));
                pipeType.behaviourTypeId().ifPresent(id -> server.create(
                    BuildCraftApi.registry(BuildCraftRegistries.PIPE_BEHAVIOUR_TYPES).get(id).componentType().id()));
                for (PipeComponentType<?> type : types.values()) {
                    server.component(type.id()).ifPresent(value -> {
                        created.add(value);
                        bindChannels(type, value);
                    });
                }
            }
        }
        components = List.copyOf(created);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PersistentType<PipeComponent, OpaqueData> persistence(PipeComponentType<?> type) {
        return (PersistentType) type.persistence().orElse(null);
    }

    private void bindLifecycle(PipeComponent value) {
        if (value instanceof PipeComponentLifecycle lifecycle) lifecycle.attached(pipe);
    }

    private <C extends PipeComponent> void bindChannels(PipeComponentType<C> type, PipeComponent component) {
        @SuppressWarnings("unchecked") C typed = (C) component;
        for (PipeComponentSync<C, ?> sync : type.sync()) bindChannel(type.id(), typed, sync);
    }

    private <C extends PipeComponent, T> void bindChannel(ResourceLocation componentId, C component,
        PipeComponentSync<C, T> sync) {
        PipeSyncChannel<?> registered = BuildCraftApi.registry(BuildCraftRegistries.PIPE_SYNC_CHANNELS).get(sync.channel().id());
        if (registered == null || !registered.equals(sync.channel())) {
            throw new IllegalStateException("Unregistered pipe sync channel: " + sync.channel().id());
        }
        Channel<T> channel = new Channel<>(componentId, sync.channel(), sync.policy(),
            () -> sync.snapshot().apply(component), value -> sync.apply().accept(component, value));
        if (channels.putIfAbsent(channel.channel.id(), channel) != null) {
            throw new IllegalStateException("Pipe sync channel used by multiple components: " + channel.channel.id());
        }
    }

    public List<PipeComponent> components() { return components; }

    public <T> java.util.Optional<T> presentation(ResourceLocation channelId, Class<T> type) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(type, "type");
        if (!client || clientInvalidated) return java.util.Optional.empty();
        Channel<?> channel = channels.get(channelId);
        Object snapshot = channel == null ? null : channel.lastReceived;
        return type.isInstance(snapshot) ? java.util.Optional.of(type.cast(snapshot)) : java.util.Optional.empty();
    }

    public void placed() {
        if (client || placed || removed) return;
        placed = true;
        notifyComponents("placed", lifecycle -> lifecycle.placed());
    }

    public void connectionsChanged(PipeConnectionChange change) {
        if (client || removed || server.phase() != RuntimeExtensions.Phase.ACTIVE) return;
        notifyComponents("connectionsChanged", lifecycle -> lifecycle.connectionsChanged(change));
    }

    public void removed() {
        if (client || removed) return;
        removed = true;
        notifyComponents("removed", PipeComponentLifecycle::removed);
    }

    public void addDrops(List<ItemStack> drops, int fortune) {
        if (client) return;
        for (PipeComponent component : components) {
            if (!(component instanceof PipeComponentLifecycle lifecycle)) continue;
            List<ItemStack> additions = new ArrayList<>();
            try {
                lifecycle.addDrops(additions, fortune);
                for (ItemStack stack : additions) {
                    if (stack != null && !stack.isEmpty()) drops.add(stack.copy());
                }
            } catch (RuntimeException ex) { report("Component drops failed", ex); }
        }
    }

    private void notifyComponents(String operation, Consumer<PipeComponentLifecycle> callback) {
        for (PipeComponent component : components) {
            if (!(component instanceof PipeComponentLifecycle lifecycle)) continue;
            try { callback.accept(lifecycle); }
            catch (RuntimeException ex) { report("Pipe component " + operation + " failed", ex); }
        }
    }

    public void onLoad() {
        if (client || server.phase() == RuntimeExtensions.Phase.ACTIVE) return;
        if (server.phase() == RuntimeExtensions.Phase.ATTACHED || server.phase() == RuntimeExtensions.Phase.SUSPENDED) {
            server.activate();
        }
    }

    public void tick() {
        if (client) {
            if (clientInvalidated) return;
            for (PipeComponent component : components) {
                if (component instanceof PipeRuntimeComponent) continue; // Server-authoritative custom flows.
                if (component instanceof PipeTickComponent ticking) {
                    try { ticking.tick(pipe); }
                    catch (RuntimeException ex) { report("Client component tick failed", ex); }
                }
            }
        } else {
            onLoad();
            if (server.phase() == RuntimeExtensions.Phase.ACTIVE) {
                server.tick();
            }
        }
    }

    public void postAttachmentTick() {
        if (client || removed || server.phase() != RuntimeExtensions.Phase.ACTIVE) return;
        for (PipeComponent component : components) {
            if (!(component instanceof PipeRuntimeComponent hook)) continue;
            long previous = activeCallback;
            activeCallback = ++callbackSerial;
            try { hook.postAttachmentTick(pipe); }
            catch (RuntimeException ex) { report("Custom flow/behaviour postAttachmentTick failed", ex); }
            finally { activeCallback = previous; }
        }
    }

    /** Unique epoch of the current component callback, or zero outside a callback. */
    public long callbackEpoch() { return !client && !removed ? activeCallback : 0L; }
    public boolean inCallback() { return callbackEpoch() != 0L; }

    public void onChunkUnloaded() {
        if (!client && server.phase() == RuntimeExtensions.Phase.ACTIVE) server.suspend();
    }

    public void invalidate() {
        if (client) {
            if (clientInvalidated) return;
            clientInvalidated = true;
            for (PipeComponent component : components) {
                if (component instanceof PipeComponentLifecycle lifecycle) {
                    try { lifecycle.invalidated(); } catch (RuntimeException ex) { report("Component invalidation failed", ex); }
                }
            }
        } else {
            server.invalidate();
        }
    }

    public byte[] save() {
        if (client) throw new IllegalStateException("Cannot save pipe components on the client");
        if (unreadableState != null) return unreadableState.clone();
        List<EncodedPayload<OpaqueData>> saved = server.save();
        if (saved.isEmpty()) return new byte[0];
        try {
            return writeState(saved);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot encode pipe components", ex);
        }
    }

    public boolean requestSync(ResourceLocation channelId) {
        if (client) throw new IllegalStateException("Cannot request component sync on the client");
        if (!channels.containsKey(Objects.requireNonNull(channelId, "channelId"))) return false;
        requested.add(channelId);
        return true;
    }

    public boolean stateChanged() {
        if (client) return false;
        for (Channel<?> channel : channels.values()) {
            if (channel.policy == PipeComponentSync.Policy.WHEN_CHANGED) requested.add(channel.channel.id());
        }
        return !requested.isEmpty();
    }

    public void writeFullSync(FriendlyByteBuf buffer) {
        writeSync(buffer, true, channels.keySet());
    }

    public void writeChangedSync(FriendlyByteBuf buffer) {
        writeSync(buffer, false, requested);
        requested.clear();
    }

    private void writeSync(FriendlyByteBuf buffer, boolean full, Collection<ResourceLocation> selected) {
        if (client) throw new IllegalStateException("Cannot write client component snapshots");
        List<WireValue> values = new ArrayList<>();
        for (ResourceLocation id : selected) {
            Channel<?> channel = channels.get(id);
            if (channel == null) continue;
            try {
                OpaqueData data = channel.capture();
                if (data.size() > Math.min(MAX_ENTRY_BYTES, channel.channel.maxBytes())) {
                    throw new IllegalArgumentException("Sync payload exceeds channel limit");
                }
                values.add(new WireValue(channel.componentId, id, data));
            } catch (RuntimeException ex) {
                report("Cannot capture sync channel " + id, ex);
            }
        }
        try {
            buffer.writeByteArray(writeWire(++outgoingRevision, full, values));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot encode pipe component sync", ex);
        }
    }

    public void readSync(FriendlyByteBuf buffer) throws IOException {
        if (!client || clientInvalidated) throw new IOException("Cannot apply sync to server or invalid pipe");
        SyncFrame frame = readWire(buffer.readByteArray(MAX_STATE_BYTES));
        if (frame.revision <= incomingRevision) return;
        List<Runnable> pending = new ArrayList<>();
        for (WireValue value : frame.values) {
            Channel<?> channel = channels.get(value.channelId);
            if (channel == null || !channel.componentId.equals(value.componentId)) continue;
            pending.add(channel.decode(value.data));
        }
        if (frame.full) {
            for (Channel<?> channel : channels.values()) channel.lastReceived = null;
        }
        for (Runnable operation : pending) {
            try {
                operation.run();
            } catch (RuntimeException ex) {
                report("Pipe component sync callback failed", ex);
            }
        }
        incomingRevision = frame.revision;
    }

    private static final class Channel<T> {
        private final ResourceLocation componentId;
        private final PipeSyncChannel<T> channel;
        private final PipeComponentSync.Policy policy;
        private final Supplier<? extends T> snapshot;
        private final Consumer<? super T> apply;
        private T lastReceived;
        private Channel(ResourceLocation componentId, PipeSyncChannel<T> channel, PipeComponentSync.Policy policy,
            Supplier<? extends T> snapshot, Consumer<? super T> apply) {
            this.componentId = componentId;
            this.channel = channel;
            this.policy = policy;
            this.snapshot = snapshot;
            this.apply = apply;
        }
        private OpaqueData capture() {
            CodecResult<OpaqueData> result = channel.codec().encode(snapshot.get());
            if (!result.successful()) throw new IllegalArgumentException("Sync encode failed: " + result.errors());
            return result.valueOrThrow();
        }
        private Runnable decode(OpaqueData data) throws IOException {
            if (data.size() > Math.min(MAX_ENTRY_BYTES, channel.maxBytes())) throw new IOException("Oversized pipe sync entry");
            CodecResult<T> result;
            try {
                result = channel.codec().decode(data);
            } catch (RuntimeException ex) {
                throw new IOException("Pipe sync decoding failed: " + channel.id(), ex);
            }
            if (!result.successful()) throw new IOException("Pipe sync decoding failed: " + result.errors());
            T value = result.valueOrThrow();
            return () -> {
                apply.accept(value);
                lastReceived = value;
            };
        }
    }

    private record WireValue(ResourceLocation componentId, ResourceLocation channelId, OpaqueData data) { }
    private record SyncFrame(long revision, boolean full, List<WireValue> values) { }

    private static byte[] writeState(List<EncodedPayload<OpaqueData>> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(STATE_MAGIC);
        out.writeByte(STATE_VERSION);
        out.writeShort(entries.size());
        for (EncodedPayload<OpaqueData> entry : entries) {
            writeId(out, entry.typeId());
            out.writeInt(entry.schemaVersion());
            writeData(out, entry.payload());
        }
        out.flush();
        if (bytes.size() > MAX_STATE_BYTES) throw new IOException("Pipe component state too large");
        return bytes.toByteArray();
    }

    private static List<EncodedPayload<OpaqueData>> readState(byte[] bytes) throws IOException {
        if (bytes.length > MAX_STATE_BYTES) throw new IOException("Oversized pipe component state");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != STATE_MAGIC || in.readUnsignedByte() != STATE_VERSION) {
            throw new IOException("Unsupported pipe component state format");
        }
        int count = in.readUnsignedShort();
        if (count > MAX_ENTRIES) throw new IOException("Too many saved pipe components");
        List<EncodedPayload<OpaqueData>> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation id = readId(in);
            int schemaVersion = in.readInt();
            if (schemaVersion < 0) throw new IOException("Invalid component schema version");
            entries.add(new EncodedPayload<>(id, schemaVersion, readData(in)));
        }
        if (in.available() != 0) throw new IOException("Trailing bytes in pipe component state");
        return entries;
    }

    private static byte[] writeWire(long revision, boolean full, List<WireValue> values) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(revision);
        out.writeBoolean(full);
        out.writeShort(values.size());
        for (WireValue value : values) {
            writeId(out, value.componentId);
            writeId(out, value.channelId);
            writeData(out, value.data);
        }
        out.flush();
        if (bytes.size() > MAX_STATE_BYTES) throw new IOException("Pipe sync packet too large");
        return bytes.toByteArray();
    }

    private static SyncFrame readWire(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        long revision = in.readLong();
        boolean full = in.readBoolean();
        int count = in.readUnsignedShort();
        if (revision < 0 || count > MAX_ENTRIES) throw new IOException("Invalid pipe component sync header");
        List<WireValue> values = new ArrayList<>(count);
        Set<ResourceLocation> seen = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation componentId = readId(in);
            ResourceLocation channelId = readId(in);
            if (!seen.add(channelId)) throw new IOException("Duplicate pipe sync channel: " + channelId);
            values.add(new WireValue(componentId, channelId, readData(in)));
        }
        if (in.available() != 0) throw new IOException("Trailing bytes in pipe component sync");
        return new SyncFrame(revision, full, List.copyOf(values));
    }

    private static void writeId(DataOutputStream out, ResourceLocation id) throws IOException {
        String name = id.toString();
        if (name.length() > 256) throw new IOException("Identifier too long");
        out.writeUTF(name);
    }

    private static ResourceLocation readId(DataInputStream in) throws IOException {
        String name = in.readUTF();
        if (name.length() > 256) throw new IOException("Identifier too long");
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) throw new IOException("Invalid identifier: " + name);
        return id;
    }

    private static void writeData(DataOutputStream out, OpaqueData data) throws IOException {
        if (data.size() > MAX_ENTRY_BYTES) throw new IOException("Pipe component payload too large");
        writeId(out, data.format());
        out.writeInt(data.size());
        out.write(data.bytes());
    }

    private static OpaqueData readData(DataInputStream in) throws IOException {
        ResourceLocation format = readId(in);
        int size = in.readInt();
        if (size < 0 || size > MAX_ENTRY_BYTES || size > in.available()) throw new IOException("Invalid pipe component payload length");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return new OpaqueData(format, bytes);
    }

    private static void report(String message, Throwable error) {
        BCLog.logger.warn(message, error);
    }
}
