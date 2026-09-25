package buildcraft.transport.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.EncodedPayload;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.persistence.SchemaMigration;
import buildcraft.api.v2.pipe.PipeComponent;
import buildcraft.api.v2.pipe.PipeComponentState;
import buildcraft.api.v2.pipe.PipeComponentType;
import buildcraft.api.v2.pipe.PipeSyncBinding;
import buildcraft.api.v2.pipe.PipeSyncChannel;
import buildcraft.api.v2.pipe.PipeSyncComponent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Shared runtime wiring for API2 pipe component state and typed sync channels. */
public final class ApiPipeComponentRuntime {
    public static final String NBT_KEY = "api_components";
    private static final int MAX_WIRE_PAYLOAD = 1024 * 1024;

    private ApiPipeComponentRuntime() {
    }

    public record StoredState(ResourceLocation componentId, EncodedPayload<OpaqueData> payload) {
        public StoredState {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(payload, "payload");
        }
    }

    public static List<PipeComponent> readState(
        CompoundTag pipeTag,
        List<PipeComponent> created,
        Map<ResourceLocation, StoredState> unresolved
    ) {
        Objects.requireNonNull(pipeTag, "pipeTag");
        Objects.requireNonNull(created, "created");
        Objects.requireNonNull(unresolved, "unresolved");
        unresolved.clear();

        List<PipeComponent> result = new ArrayList<>(created);
        Map<ResourceLocation, Integer> indices = new LinkedHashMap<>();
        for (int i = 0; i < result.size(); i++) indices.put(result.get(i).typeId(), i);

        ListTag entries = pipeTag.getList(NBT_KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            ResourceLocation componentId = ResourceLocation.tryParse(entry.getString("component"));
            ResourceLocation storedType = ResourceLocation.tryParse(entry.getString("type"));
            ResourceLocation format = ResourceLocation.tryParse(entry.getString("format"));
            int schema = entry.getInt("schema");
            if (componentId == null || storedType == null || format == null || schema < 0) continue;

            StoredState stored = new StoredState(
                componentId,
                new EncodedPayload<>(storedType, schema, new OpaqueData(format, entry.getByteArray("payload")))
            );
            Integer index = indices.get(componentId);
            if (index == null) {
                unresolved.put(componentId, stored);
                continue;
            }
            PipeComponent current = result.get(index);
            PipeComponent decoded = decodeInto(current, stored.payload());
            if (decoded == null) {
                unresolved.put(componentId, stored);
            } else if (decoded != current) {
                result.set(index, decoded);
            }
        }
        return result;
    }

    public static void writeState(
        CompoundTag pipeTag,
        Collection<PipeComponent> components,
        Map<ResourceLocation, StoredState> unresolved
    ) {
        Objects.requireNonNull(pipeTag, "pipeTag");
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(unresolved, "unresolved");

        LinkedHashMap<ResourceLocation, StoredState> entries = new LinkedHashMap<>(unresolved);
        for (PipeComponent component : components) {
            StoredState state = encode(component);
            if (state != null) entries.put(component.typeId(), state);
        }
        if (entries.isEmpty()) {
            pipeTag.remove(NBT_KEY);
            return;
        }

        ListTag list = new ListTag();
        for (StoredState state : entries.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("component", state.componentId().toString());
            entry.putString("type", state.payload().typeId().toString());
            entry.putInt("schema", state.payload().schemaVersion());
            entry.putString("format", state.payload().payload().format().toString());
            entry.putByteArray("payload", state.payload().payload().bytes());
            list.add(entry);
        }
        pipeTag.put(NBT_KEY, list);
    }

    public static boolean hasSyncBinding(Collection<PipeComponent> components, ResourceLocation channelId) {
        return findBindings(components, channelId).size() > 0;
    }

    public static void writeInitialSync(FriendlyByteBuf buffer, Collection<PipeComponent> components) {
        writeSync(buffer, components, null);
    }

    public static void writeRequestedSync(
        FriendlyByteBuf buffer,
        Collection<PipeComponent> components,
        Set<ResourceLocation> requested
    ) {
        writeSync(buffer, components, Objects.requireNonNull(requested, "requested"));
    }

    public static void readSync(FriendlyByteBuf buffer, Collection<PipeComponent> components) throws IOException {
        int count = buffer.readVarInt();
        if (count < 0 || count > 1024) throw new IOException("Invalid API2 pipe sync entry count: " + count);
        for (int i = 0; i < count; i++) {
            ResourceLocation channelId = buffer.readResourceLocation();
            ResourceLocation format = buffer.readResourceLocation();
            int size = buffer.readVarInt();
            if (size < 0 || size > MAX_WIRE_PAYLOAD) {
                throw new IOException("Invalid API2 pipe sync payload size " + size + " for " + channelId);
            }
            byte[] bytes = new byte[size];
            buffer.readBytes(bytes);
            PipeSyncChannel<?> channel = BuildCraftApi.registry(BuildCraftRegistries.PIPE_SYNC_CHANNELS).get(channelId);
            if (channel == null) continue;
            if (size > channel.maxBytes()) {
                throw new IOException("API2 pipe sync payload for " + channelId + " exceeds maxBytes=" + channel.maxBytes());
            }
            applyPayload(components, channel, new OpaqueData(format, bytes));
        }
    }

    private static void writeSync(
        FriendlyByteBuf buffer,
        Collection<PipeComponent> components,
        Set<ResourceLocation> requested
    ) {
        List<EncodedSync> encoded = new ArrayList<>();
        for (PipeComponent component : components) {
            if (!(component instanceof PipeSyncComponent sync)) continue;
            for (PipeSyncBinding<?> binding : sync.syncBindings()) {
                if (binding == null || binding.channel() == null) continue;
                PipeSyncChannel<?> channel = binding.channel();
                if (requested != null && !requested.contains(channel.id())) continue;
                PipeSyncChannel<?> registered = BuildCraftApi.registry(BuildCraftRegistries.PIPE_SYNC_CHANNELS).get(channel.id());
                if (registered == null) {
                    throw new IllegalStateException("Pipe component " + component.typeId() + " uses unregistered sync channel " + channel.id());
                }
                OpaqueData payload = encodeBinding(binding);
                if (payload.size() > channel.maxBytes()) {
                    throw new IllegalStateException(
                        "Pipe sync payload for " + channel.id() + " is " + payload.size() + " bytes, max " + channel.maxBytes()
                    );
                }
                encoded.add(new EncodedSync(channel.id(), payload));
            }
        }
        buffer.writeVarInt(encoded.size());
        for (EncodedSync entry : encoded) {
            buffer.writeResourceLocation(entry.channelId());
            buffer.writeResourceLocation(entry.payload().format());
            byte[] bytes = entry.payload().bytes();
            buffer.writeVarInt(bytes.length);
            buffer.writeBytes(bytes);
        }
    }

    private static StoredState encode(PipeComponent component) {
        PipeComponentType<?> raw = BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).get(component.typeId());
        if (raw == null) return null;
        return encodeTyped(raw, component);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static StoredState encodeTyped(PipeComponentType raw, PipeComponent component) {
        Optional<PipeComponentState> stateBinding = raw.statePersistence();
        if (stateBinding.isPresent()) {
            PipeComponentState binding = stateBinding.get();
            PersistentType persistence = binding.persistence();
            Object state = binding.snapshot(component);
            CodecResult<OpaqueData> encoded = persistence.codec().encode(state);
            if (!encoded.successful()) return null;
            return new StoredState(component.typeId(), new EncodedPayload<>(
                persistence.id(), persistence.schemaVersion(), encoded.valueOrThrow()
            ));
        }
        Optional<PersistentType> whole = raw.persistence();
        if (whole.isEmpty()) return null;
        PersistentType persistence = whole.get();
        CodecResult<OpaqueData> encoded = persistence.codec().encode(component);
        if (!encoded.successful()) return null;
        return new StoredState(component.typeId(), new EncodedPayload<>(
            persistence.id(), persistence.schemaVersion(), encoded.valueOrThrow()
        ));
    }

    private static PipeComponent decodeInto(PipeComponent current, EncodedPayload<OpaqueData> stored) {
        PipeComponentType<?> raw = BuildCraftApi.registry(BuildCraftRegistries.PIPE_COMPONENT_TYPES).get(current.typeId());
        if (raw == null) return null;
        return decodeTyped(raw, current, stored);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static PipeComponent decodeTyped(PipeComponentType raw, PipeComponent current, EncodedPayload<OpaqueData> stored) {
        Optional<PipeComponentState> stateBinding = raw.statePersistence();
        if (stateBinding.isPresent()) {
            PipeComponentState binding = stateBinding.get();
            PersistentType persistence = binding.persistence();
            OpaqueData payload = resolvePayload(persistence, stored);
            if (payload == null) return null;
            CodecResult decoded = persistence.codec().decode(payload);
            if (!decoded.successful()) return null;
            binding.apply(current, decoded.valueOrThrow());
            return current;
        }
        Optional<PersistentType> whole = raw.persistence();
        if (whole.isEmpty()) return null;
        PersistentType persistence = whole.get();
        OpaqueData payload = resolvePayload(persistence, stored);
        if (payload == null) return null;
        CodecResult decoded = persistence.codec().decode(payload);
        if (!decoded.successful() || !(decoded.valueOrThrow() instanceof PipeComponent replacement)) return null;
        return current.typeId().equals(replacement.typeId()) ? replacement : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OpaqueData resolvePayload(PersistentType persistence, EncodedPayload<OpaqueData> stored) {
        ResourceLocation storedType = stored.typeId();
        if (!persistence.id().equals(storedType) && !persistence.aliases().contains(storedType)) return null;
        if (stored.schemaVersion() > persistence.schemaVersion()) return null;
        OpaqueData payload = stored.payload();
        int schema = stored.schemaVersion();
        while (schema < persistence.schemaVersion()) {
            SchemaMigration next = null;
            for (Object rawMigration : persistence.migrations()) {
                SchemaMigration migration = (SchemaMigration) rawMigration;
                if (migration.fromVersion() == schema) {
                    next = migration;
                    break;
                }
            }
            if (next == null) return null;
            CodecResult<OpaqueData> migrated = next.migrate(payload);
            if (!migrated.successful()) return null;
            payload = migrated.valueOrThrow();
            schema = next.toVersion();
        }
        return payload;
    }

    private static List<PipeSyncBinding<?>> findBindings(Collection<PipeComponent> components, ResourceLocation channelId) {
        List<PipeSyncBinding<?>> result = new ArrayList<>();
        for (PipeComponent component : components) {
            if (!(component instanceof PipeSyncComponent sync)) continue;
            for (PipeSyncBinding<?> binding : sync.syncBindings()) {
                if (binding != null && binding.channel() != null && channelId.equals(binding.channel().id())) result.add(binding);
            }
        }
        return result;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static OpaqueData encodeBinding(PipeSyncBinding binding) {
        PipeSyncChannel channel = binding.channel();
        CodecResult<OpaqueData> encoded = channel.codec().encode(binding.snapshot());
        if (!encoded.successful()) {
            throw new IllegalStateException("Failed to encode pipe sync channel " + channel.id() + ": " + encoded.errors());
        }
        return encoded.valueOrThrow();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyPayload(Collection<PipeComponent> components, PipeSyncChannel channel, OpaqueData payload) {
        CodecResult decoded = channel.codec().decode(payload);
        if (!decoded.successful()) return;
        Object value = decoded.valueOrThrow();
        for (PipeSyncBinding<?> rawBinding : findBindings(components, channel.id())) {
            PipeSyncBinding binding = rawBinding;
            binding.apply(value);
        }
    }

    private record EncodedSync(ResourceLocation channelId, OpaqueData payload) {
    }
}
