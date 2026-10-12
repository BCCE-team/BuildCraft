package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.fluid.FluidPort;
import buildcraft.api.v2.fuels.EnergyFluidService;
import buildcraft.api.v2.item.ItemPort;
import buildcraft.api.v2.machine.HeatPort;
import buildcraft.api.v2.machine.MachineActionComponent;
import buildcraft.api.v2.machine.MachineAreaView;
import buildcraft.api.v2.machine.MachineComponent;
import buildcraft.api.v2.machine.MachineComponentLifecycle;
import buildcraft.api.v2.machine.MachineComponentPresentation;
import buildcraft.api.v2.machine.MachineComponentType;
import buildcraft.api.v2.machine.MachineControl;
import buildcraft.api.v2.machine.MachineControlMode;
import buildcraft.api.v2.machine.MachineHost;
import buildcraft.api.v2.machine.MachineHostOwner;
import buildcraft.api.v2.machine.MachineTickComponent;
import buildcraft.api.v2.machine.MachineType;
import buildcraft.api.v2.machine.MachineWorldAccess;
import buildcraft.api.v2.machine.WorkState;
import buildcraft.api.v2.machine.WorkStatus;
import buildcraft.api.v2.persistence.EncodedPayload;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.platform.ExternalEnergyPort;
import buildcraft.api.v2.recipe.MachineRecipeService;
import buildcraft.lib.internal.api.v2.runtime.RuntimeExtensions;
import buildcraft.lib.internal.debug.BCLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Owns machine components in an addon block entity. No separate machine scheduler or resource storage is created. */
class MachineRuntimeHost implements MachineHost {
    private static final int STATE_MAGIC = 0x42434d48;
    private static final int SYNC_MAGIC = 0x42434d53;
    private static final int VERSION = 1;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_ENTRY_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_BYTES = 1_200_000;
    private static final int MAX_ACTION_BYTES = 4096;
    private static final int MAX_DETAIL_LENGTH = 256;
    private static final WorkStatus DEFAULT_STATUS = new WorkStatus(WorkState.IDLE, 0, "");

    private final Thread ownerThread = Thread.currentThread();
    private final MachineType type;
    private final MachineHostOwner owner;
    private final boolean client;
    private final Map<ResourceLocation, MachineComponentType<?>> types = new LinkedHashMap<>();
    private final RuntimeExtensions<MachineComponent, Object> runtime;
    private MachineControlMode controlMode = MachineControlMode.ON;
    private WorkStatus status = DEFAULT_STATUS;
    private byte[] undecodableState;
    private boolean initialized;
    private boolean removed;
    private boolean invalidated;
    private boolean statusDirty = true;
    private long outgoingRevision;
    private long incomingRevision = -1;

    MachineRuntimeHost(MachineType type, MachineHostOwner owner) {
        this(type, owner, Map.of());
    }

    /** Inline types are bound to a registered engine, never installed in global machine registries. */
    MachineRuntimeHost(MachineType type, MachineHostOwner owner,
        Map<ResourceLocation, MachineComponentType<?>> inline) {
        this.type = Objects.requireNonNull(type, "type");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.client = owner.clientSide();
        Objects.requireNonNull(inline, "inline");
        if (inline.isEmpty()) {
            if (BuildCraftApi.registry(BuildCraftRegistries.MACHINE_TYPES).get(type.id()) != type) {
                throw new IllegalArgumentException("Machine type must be registered: " + type.id());
            }
        }
        if (!type.components().containsAll(inline.keySet())) {
            throw new IllegalArgumentException("Inline engine components must belong to their machine definition");
        }
        List<RuntimeExtensions.Definition<MachineComponent, Object>> definitions = new ArrayList<>();
        for (ResourceLocation id : type.components()) {
            MachineComponentType<?> componentType = inline.get(id);
            if (componentType == null) componentType = BuildCraftApi.registry(BuildCraftRegistries.MACHINE_COMPONENT_TYPES).get(id);
            if (componentType == null || componentType.factory().isEmpty()) continue;
            types.put(id, componentType);
            definitions.add(definition(componentType));
        }
        runtime = new RuntimeExtensions<>(definitions,
            client ? RuntimeExtensions.Side.CLIENT : RuntimeExtensions.Side.SERVER,
            this::reportDiagnostic);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private RuntimeExtensions.Definition<MachineComponent, Object> definition(MachineComponentType<?> type) {
        MachineComponentPresentation<?, ?> view = type.presentation().orElse(null);
        PersistentType<Object, OpaqueData> presentation = view == null ? null : (PersistentType) view.codec();
        java.util.function.Function<MachineComponent, Object> capture = view == null ? null
            : component -> ((java.util.function.Function) view.snapshot()).apply(component);
        return new RuntimeExtensions.Definition<>(type.id(),
            () -> (MachineComponent) type.factory().orElseThrow().create(this),
            (PersistentType) type.persistence(), presentation, capture,
            new RuntimeExtensions.Callbacks<>() {
                @Override public void attach(MachineComponent value) {
                    if (!type.id().equals(value.typeId())) throw new IllegalArgumentException("Wrong machine component id");
                    if (value instanceof MachineComponentLifecycle lifecycle) lifecycle.attached(MachineRuntimeHost.this);
                }
                @Override public void load(MachineComponent value) {
                    if (value instanceof MachineComponentLifecycle lifecycle) lifecycle.restored();
                }
                @Override public void activate(MachineComponent value) {
                    if (value instanceof MachineComponentLifecycle lifecycle) lifecycle.activated();
                }
                @Override public void suspend(MachineComponent value) {
                    if (value instanceof MachineComponentLifecycle lifecycle) lifecycle.suspended();
                }
                @Override public void tick(MachineComponent value) {
                    if (value instanceof MachineTickComponent ticking) ticking.tick(MachineRuntimeHost.this);
                }
                @Override public void invalidate(MachineComponent value) {
                    if (value instanceof MachineComponentLifecycle lifecycle) lifecycle.invalidated();
                }
            });
    }

    private void reportDiagnostic(String message) {
        BCLog.logger.warn("Machine {}: {}", type.id(), message);
        if (!client && initialized && message.contains(" failed:")) {
            status = new WorkStatus(WorkState.ERROR, 0, "Component execution failed");
            statusDirty = true;
            owner.machineChanged();
        }
    }

    private void checkThread() {
        if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Machine accessed off its level thread");
        if (invalidated) throw new IllegalStateException("Machine has been invalidated");
    }
    private void server() {
        checkThread();
        if (client) throw new IllegalStateException("Server-only machine operation");
    }
    private void client() {
        checkThread();
        if (!client) throw new IllegalStateException("Client-only machine operation");
    }

    private void initialize(List<EncodedPayload<OpaqueData>> restored) {
        runtime.restore(restored);
        for (ResourceLocation id : type.components()) {
            if (types.containsKey(id)) runtime.create(id);
        }
        initialized = true;
    }

    @Override public ResourceLocation typeId() { return type.id(); }
    @Override public MachineType definition() { return type; }
    @Override public BlockPos position() { return owner.position(); }
    @Override public boolean isServerSide() { return !client; }
    @Override public boolean isActive() { return !invalidated && !removed && initialized && !client
        && runtime.phase() == RuntimeExtensions.Phase.ACTIVE; }
    @Override public WorkStatus workStatus() { return status; }
    @Override public MachineControlMode mode() { return controlMode; }
    @Override public Optional<MachineControl> control() { return Optional.of(this); }
    @Override public Set<MachineControlMode> supportedModes() { return Set.copyOf(EnumSet.allOf(MachineControlMode.class)); }
    @Override public Optional<ItemPort> itemPort(Direction side) { return owner.itemPort(side); }
    @Override public Optional<FluidPort> fluidPort(Direction side) { return owner.fluidPort(side); }
    @Override public Optional<MjPort> mjPort(Direction side) { return owner.mjPort(side); }
    @Override public Optional<ExternalEnergyPort> externalEnergyPort(Direction side) { return owner.externalEnergyPort(side); }
    @Override public Optional<HeatPort> heatPort() { return owner.heatPort(); }
    @Override public Optional<MachineAreaView> area() { return owner.area(); }
    @Override public MachineRecipeService recipes() { return BuildCraftApi.service(BuildCraftServices.MACHINE_RECIPES); }
    @Override public EnergyFluidService energyFluids() { return BuildCraftApi.service(BuildCraftServices.ENERGY_FLUIDS); }

    @Override public Collection<MachineComponent> machineComponents() {
        checkThread();
        if (client || !initialized) return List.of();
        List<MachineComponent> result = new ArrayList<>();
        for (ResourceLocation id : types.keySet()) runtime.component(id).ifPresent(result::add);
        return List.copyOf(result);
    }
    @Override public <C extends MachineComponent> Optional<C> component(ResourceLocation id, Class<C> clazz) {
        server();
        Objects.requireNonNull(clazz, "clazz");
        if (!initialized) return Optional.empty();
        return runtime.component(Objects.requireNonNull(id, "id")).filter(clazz::isInstance).map(clazz::cast);
    }
    @Override public <T> Optional<T> presentation(ResourceLocation id, Class<T> clazz) {
        client();
        Objects.requireNonNull(clazz, "clazz");
        return runtime.view(Objects.requireNonNull(id, "id")).filter(clazz::isInstance).map(clazz::cast);
    }
    @Override public boolean setMode(MachineControlMode next, OperationMode operation) {
        server();
        Objects.requireNonNull(next, "mode");
        Objects.requireNonNull(operation, "operation");
        if (!supportedModes().contains(next) || removed || undecodableState != null) return false;
        if (operation == OperationMode.EXECUTE && controlMode != next) {
            controlMode = next;
            if (next == MachineControlMode.OFF) status = new WorkStatus(WorkState.PAUSED, status.progress(), "");
            statusDirty = true;
            owner.machineChanged();
        }
        return true;
    }
    @Override public void setWorkStatus(WorkStatus next) {
        server();
        Objects.requireNonNull(next, "status");
        if (!isActive() || removed || undecodableState != null) throw new IllegalStateException("Inactive machine");
        if (!status.equals(next)) {
            status = next;
            statusDirty = true;
            owner.machineChanged();
        }
    }
    @Override public void markChanged() {
        server();
        if (!isActive() || removed || undecodableState != null) throw new IllegalStateException("Inactive machine");
        for (ResourceLocation id : types.keySet()) {
            if (runtime.component(id).isPresent()) runtime.markDirty(id);
        }
        owner.machineChanged();
    }
    @Override public boolean isBlockLoaded(BlockPos pos) {
        checkThread();
        Level level = owner.level();
        return level != null && level.hasChunkAt(Objects.requireNonNull(pos, "pos"));
    }
    @Override public Optional<BlockState> blockState(BlockPos pos) {
        return isBlockLoaded(pos) ? Optional.of(owner.level().getBlockState(pos)) : Optional.empty();
    }
    @Override public boolean setBlock(BlockPos pos, BlockState state) {
        server();
        Objects.requireNonNull(pos, "pos"); Objects.requireNonNull(state, "state");
        if (!isActive() || removed || controlMode == MachineControlMode.OFF || !isBlockLoaded(pos)) return false;
        MachineWorldAccess access = owner.worldAccess().orElse(null);
        if (access == null || !access.setBlock(pos, state)) return false;
        markChanged();
        return true;
    }

    @Override public void restoreState(byte[] bytes) {
        server();
        if (initialized) throw new IllegalStateException("Machine already restored");
        byte[] input = Objects.requireNonNull(bytes, "saved").clone();
        if (input.length == 0) {
            initialize(List.of());
            return;
        }
        try {
            State state = decodeState(input);
            if (!type.id().equals(state.typeId)) throw new IOException("Machine type id differs from saved state");
            controlMode = state.mode;
            status = state.status;
            initialize(state.components);
        } catch (IOException | RuntimeException ex) {
            // Never overwrite state from an unavailable/corrupt codec with a fresh default instance.
            undecodableState = input;
            status = new WorkStatus(WorkState.ERROR, 0, "Stored state is unavailable");
            BCLog.logger.warn("Cannot restore machine {} at {}; preserving original state", type.id(), position(), ex);
        }
    }
    @Override public byte[] saveState() {
        server();
        if (undecodableState != null) return undecodableState.clone();
        if (!initialized) initialize(List.of());
        try {
            return encodeState(type.id(), controlMode, status, runtime.save());
        } catch (IOException ex) { throw new IllegalStateException("Cannot save machine", ex); }
    }
    @Override public void onLoad() {
        checkThread();
        if (client || removed || undecodableState != null) return;
        if (!initialized) initialize(List.of());
        RuntimeExtensions.Phase phase = runtime.phase();
        if (phase == RuntimeExtensions.Phase.ATTACHED || phase == RuntimeExtensions.Phase.SUSPENDED) runtime.activate();
    }
    @Override public void tick() {
        checkThread();
        if (client || removed || undecodableState != null || owner.level() == null
            || !owner.level().hasChunkAt(owner.position())) return;
        onLoad();
        if (controlMode != MachineControlMode.OFF) runtime.tick();
    }
    @Override public void onChunkUnloaded() {
        if (client || invalidated || !initialized || undecodableState != null) return;
        server();
        if (runtime.phase() == RuntimeExtensions.Phase.ACTIVE) runtime.suspend();
    }
    @Override public void addDrops(List<ItemStack> drops) {
        server();
        Objects.requireNonNull(drops, "drops");
        if (removed || !initialized || undecodableState != null) return;
        for (MachineComponent component : machineComponents()) {
            if (component instanceof MachineComponentLifecycle lifecycle) {
                List<ItemStack> local = new ArrayList<>();
                try {
                    lifecycle.addDrops(local);
                    for (ItemStack stack : local) if (stack != null && !stack.isEmpty()) drops.add(stack.copy());
                } catch (RuntimeException ex) { BCLog.logger.warn("Machine {} drop callback failed", type.id(), ex); }
            }
        }
    }
    @Override public void removed() {
        checkThread();
        if (client || removed) return;
        removed = true;
        if (initialized && undecodableState == null) {
            for (MachineComponent component : machineComponents()) {
                if (component instanceof MachineComponentLifecycle lifecycle) {
                    try { lifecycle.removed(); }
                    catch (RuntimeException ex) { BCLog.logger.warn("Machine {} removal callback failed", type.id(), ex); }
                }
            }
        }
    }
    @Override public void invalidate() {
        if (invalidated) return;
        checkThread();
        if (!client && initialized && undecodableState == null) runtime.invalidate();
        if (client) runtime.invalidate();
        invalidated = true;
    }
    @Override public boolean performAction(Player actor, ResourceLocation action, byte[] payload) {
        server();
        Objects.requireNonNull(actor, "actor"); Objects.requireNonNull(action, "action");
        byte[] copied = Objects.requireNonNull(payload, "payload").clone();
        if (removed || !isActive() || copied.length > MAX_ACTION_BYTES || !owner.canPlayerUse(actor)) return false;
        for (MachineComponent component : machineComponents()) {
            if (component instanceof MachineActionComponent handler) {
                try {
                    if (handler.handleAction(this, actor, action, copied.clone())) {
                        markChanged();
                        return true;
                    }
                } catch (RuntimeException ex) { BCLog.logger.warn("Machine {} action {} failed", type.id(), action, ex); }
            }
        }
        return false;
    }

    @Override public byte[] fullSync() {
        server();
        if (undecodableState != null) return new byte[0];
        if (!initialized) initialize(List.of());
        return encodeSync(true, runtime.fullSync());
    }
    @Override public byte[] changedSync() {
        server();
        if (!initialized || undecodableState != null) return new byte[0];
        Optional<RuntimeExtensions.SyncFrame> frame = runtime.changedSync();
        if (frame.isEmpty() && !statusDirty) return new byte[0];
        return encodeSync(false, frame.orElse(null));
    }
    private byte[] encodeSync(boolean full, RuntimeExtensions.SyncFrame frame) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(SYNC_MAGIC);
            out.writeByte(VERSION);
            writeId(out, type.id());
            out.writeLong(++outgoingRevision);
            out.writeBoolean(full);
            writeStatus(out, controlMode, status);
            out.writeBoolean(frame != null);
            if (frame != null) {
                out.writeLong(frame.revision());
                out.writeBoolean(frame.full());
                writeEntries(out, frame.data());
            }
            out.flush();
            if (buffer.size() > MAX_TOTAL_BYTES) throw new IOException("Oversized machine sync");
            statusDirty = false;
            return buffer.toByteArray();
        } catch (IOException ex) { throw new IllegalStateException("Cannot sync machine", ex); }
    }
    @Override public void applySync(byte[] packet) throws IOException {
        client();
        byte[] data = Objects.requireNonNull(packet, "packet");
        if (data.length > MAX_TOTAL_BYTES) throw new IOException("Oversized machine sync");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != SYNC_MAGIC || in.readUnsignedByte() != VERSION) throw new IOException("Wrong sync format");
        if (!type.id().equals(readId(in))) throw new IOException("Sync target mismatch");
        long revision = in.readLong();
        if (revision < 0) throw new IOException("Negative machine revision");
        boolean full = in.readBoolean();
        Status incoming = readStatus(in);
        RuntimeExtensions.SyncFrame frame = null;
        if (in.readBoolean()) frame = new RuntimeExtensions.SyncFrame(in.readLong(), in.readBoolean(), readEntries(in));
        if (in.available() != 0) throw new IOException("Trailing bytes in machine sync");
        if (revision <= incomingRevision) return;
        if (full && (frame == null || !frame.full())) throw new IOException("Full machine sync lacks component snapshot");
        if (frame != null && !runtime.applySync(frame)) throw new IOException("Cannot apply component snapshot");
        controlMode = incoming.mode;
        status = incoming.status;
        incomingRevision = revision;
    }

    private record State(ResourceLocation typeId, MachineControlMode mode, WorkStatus status,
        List<EncodedPayload<OpaqueData>> components) { }
    private record Status(MachineControlMode mode, WorkStatus status) { }
    private static byte[] encodeState(ResourceLocation typeId, MachineControlMode mode, WorkStatus status,
        List<EncodedPayload<OpaqueData>> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(STATE_MAGIC);
        out.writeByte(VERSION);
        writeId(out, typeId);
        writeStatus(out, mode, status);
        writeEntries(out, entries);
        out.flush();
        if (buffer.size() > MAX_TOTAL_BYTES) throw new IOException("Oversized machine state");
        return buffer.toByteArray();
    }
    private static State decodeState(byte[] data) throws IOException {
        if (data.length > MAX_TOTAL_BYTES) throw new IOException("Oversized machine state");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != STATE_MAGIC || in.readUnsignedByte() != VERSION) throw new IOException("Unknown machine state version");
        ResourceLocation id = readId(in);
        Status status = readStatus(in);
        List<EncodedPayload<OpaqueData>> components = readEntries(in);
        if (in.available() != 0) throw new IOException("Trailing bytes in machine state");
        return new State(id, status.mode(), status.status(), components);
    }
    private static void writeStatus(DataOutputStream out, MachineControlMode mode, WorkStatus status) throws IOException {
        out.writeByte(mode.ordinal());
        out.writeByte(status.state().ordinal());
        out.writeDouble(status.progress());
        String detail = status.detail();
        if (detail.length() > MAX_DETAIL_LENGTH) detail = detail.substring(0, MAX_DETAIL_LENGTH);
        out.writeUTF(detail);
    }
    private static Status readStatus(DataInputStream in) throws IOException {
        int mode = in.readUnsignedByte(), work = in.readUnsignedByte();
        if (mode >= MachineControlMode.values().length || work >= WorkState.values().length) {
            throw new IOException("Invalid machine control/status");
        }
        double progress = in.readDouble();
        String detail = in.readUTF();
        if (detail.length() > MAX_DETAIL_LENGTH) throw new IOException("Oversized machine status detail");
        try {
            return new Status(MachineControlMode.values()[mode], new WorkStatus(WorkState.values()[work], progress, detail));
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid machine status", ex); }
    }
    private static void writeEntries(DataOutputStream out, Collection<EncodedPayload<OpaqueData>> entries) throws IOException {
        if (entries.size() > MAX_ENTRIES) throw new IOException("Too many components");
        out.writeShort(entries.size());
        for (EncodedPayload<OpaqueData> entry : entries) {
            writeId(out, entry.typeId());
            out.writeInt(entry.schemaVersion());
            writeId(out, entry.payload().format());
            byte[] bytes = entry.payload().bytes();
            if (bytes.length > MAX_ENTRY_BYTES) throw new IOException("Component payload too large");
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }
    private static List<EncodedPayload<OpaqueData>> readEntries(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        if (count > MAX_ENTRIES) throw new IOException("Too many machine components");
        List<EncodedPayload<OpaqueData>> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ResourceLocation id = readId(in);
            int schema = in.readInt();
            if (schema < 0) throw new IOException("Negative machine schema");
            ResourceLocation format = readId(in);
            int length = in.readInt();
            if (length < 0 || length > MAX_ENTRY_BYTES || length > in.available()) throw new IOException("Invalid machine payload length");
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            entries.add(new EncodedPayload<>(id, schema, new OpaqueData(format, bytes)));
        }
        return List.copyOf(entries);
    }
    private static void writeId(DataOutputStream out, ResourceLocation id) throws IOException {
        if (id.toString().length() > 256) throw new IOException("Machine identifier too long");
        out.writeUTF(id.toString());
    }
    private static ResourceLocation readId(DataInputStream in) throws IOException {
        String name = in.readUTF();
        if (name.length() > 256) throw new IOException("Oversized machine identifier");
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null) throw new IOException("Invalid machine identifier");
        return id;
    }
}
