package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.energy.EnergyService;
import buildcraft.api.v2.energy.MjAmount;
import buildcraft.api.v2.energy.MjConnectionContext;
import buildcraft.api.v2.energy.MjPort;
import buildcraft.api.v2.energy.MjStorage;
import buildcraft.api.v2.energy.MjPortDescriptor;
import buildcraft.api.v2.energy.MjPortRole;
import buildcraft.api.v2.energy.MjTransferResult;
import buildcraft.api.v2.machine.EngineComponent;
import buildcraft.api.v2.machine.EngineExecutionContext;
import buildcraft.api.v2.machine.EngineFactory;
import buildcraft.api.v2.machine.EngineHeatSettings;
import buildcraft.api.v2.machine.EngineHost;
import buildcraft.api.v2.machine.EngineHostOwner;
import buildcraft.api.v2.machine.EngineStage;
import buildcraft.api.v2.machine.EngineType;
import buildcraft.api.v2.machine.EngineVisualState;
import buildcraft.api.v2.machine.HeatPort;
import buildcraft.api.v2.machine.HeatProfile;
import buildcraft.api.v2.machine.HeatState;
import buildcraft.api.v2.machine.MachineComponent;
import buildcraft.api.v2.machine.MachineComponentPresentation;
import buildcraft.api.v2.machine.MachineComponentType;
import buildcraft.api.v2.machine.MachineType;
import buildcraft.api.v2.machine.WorkState;
import buildcraft.api.v2.machine.WorkStatus;
import buildcraft.api.v2.persistence.ApiCodec;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PersistentType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Executes addon engines using the normal machine component scheduler, save format and sync channel. */
final class EngineRuntimeHost extends MachineRuntimeHost implements EngineHost {
    private static final ResourceLocation STATE_FORMAT = id("buildcraft:engine_state");
    private static final ResourceLocation VISUAL_FORMAT = id("buildcraft:engine_visual");
    private static final EngineStage IDLE = new EngineStage(id("buildcraft:idle"), 0);
    private static final int MAX_STAGE_ID_LENGTH = 256;
    private final EngineType engineType;
    private final EngineHostOwner owner;
    private final EngineHeatSettings heat;
    private final ResourceLocation coreId;
    private final MjPort outputBuffer;
    /** Exposes existing owner storage without accepting external input from foreign engines. */
    private final MjPort publicOutput = new MjPort() {
        @Override public MjTransferResult insert(MjAmount offered, OperationMode mode) {
            return MjTransferResult.none(offered);
        }
        @Override public MjTransferResult extract(MjAmount requested, OperationMode mode) {
            return outputBuffer.extract(requested, mode);
        }
        @Override public MjAmount stored() { return outputBuffer.stored(); }
        @Override public MjAmount capacity() { return outputBuffer.capacity(); }
        @Override public boolean canInsert() { return false; }
        @Override public boolean canExtract() { return true; }
    };
    private final HeatPort heatPort = new HeatPort() {
        @Override public HeatState heatState() { return visualState().heat(); }
        @Override public HeatProfile profile() { return heat.operatingRange(); }
        @Override public HeatState setTemperature(double temperature, OperationMode mode) {
            Objects.requireNonNull(mode, "mode");
            if (!Double.isFinite(temperature) || temperature < heat.operatingRange().minimum()) {
                throw new IllegalArgumentException("Engine temperature is invalid");
            }
            if (mode == OperationMode.EXECUTE) EngineRuntimeHost.this.setTemperature(temperature);
            return mode == OperationMode.SIMULATE
                ? new HeatState(temperature, heat.critical()) : heatState();
        }
    };
    private long producedThisTick;
    private long sentThisTick;
    private boolean ticking;

    <E extends EngineComponent> EngineRuntimeHost(EngineType type, EngineHostOwner owner,
        EngineFactory<E> factory, PersistentType<E, OpaqueData> persistence, EngineHeatSettings heat) {
        super(machineType(type), owner, inlineTypes(type, factory, persistence, heat));
        this.engineType = Objects.requireNonNull(type, "type");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.heat = Objects.requireNonNull(heat, "heat");
        this.coreId = coreId(type.id());
        this.outputBuffer = Objects.requireNonNull(owner.outputBuffer(), "outputBuffer");
        if (!outputBuffer.canInsert() || !outputBuffer.canExtract()
            || outputBuffer.capacity().compareTo(type.profile().capacity()) < 0) {
            throw new IllegalArgumentException("Engine output buffer must be read/write and meet its declared capacity");
        }
    }

    private static ResourceLocation id(String text) {
        return Objects.requireNonNull(ResourceLocation.tryParse(text));
    }

    private static ResourceLocation coreId(ResourceLocation engineId) {
        return id(engineId.getNamespace() + ":engine_core/" + engineId.getPath());
    }

    private static MachineType machineType(EngineType engine) {
        Objects.requireNonNull(engine, "engine");
        if (BuildCraftApi.registry(BuildCraftRegistries.ENGINE_TYPES).get(engine.id()) != engine) {
            throw new IllegalArgumentException("Engine type must be registered: " + engine.id());
        }
        return MachineType.builder(engine.id()).component(engine.id()).component(coreId(engine.id())).build();
    }

    private static <E extends EngineComponent> Map<ResourceLocation, MachineComponentType<?>> inlineTypes(
        EngineType type, EngineFactory<E> factory, PersistentType<E, OpaqueData> codec, EngineHeatSettings heat) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(codec, "persistence");
        Objects.requireNonNull(heat, "heat");
        if (!type.id().equals(codec.id())) {
            throw new IllegalArgumentException("Engine component persistence ID must match EngineType ID");
        }
        ResourceLocation coreId = coreId(type.id());
        MachineComponentType<E> logic = new MachineComponentType<>(type.id(), codec,
            context -> factory.create((EngineExecutionContext) context));
        MachineComponentType<Core> core = new MachineComponentType<>(coreId,
            corePersistence(coreId), context -> new Core(coreId, idleState(heat)),
            new MachineComponentPresentation<>(visualPersistence(coreId), value -> value.visual));
        Map<ResourceLocation, MachineComponentType<?>> result = new LinkedHashMap<>();
        result.put(type.id(), logic);
        result.put(coreId, core);
        return Map.copyOf(result);
    }

    private static EngineVisualState idleState(EngineHeatSettings heat) {
        return new EngineVisualState(IDLE, new HeatState(heat.ambient(), heat.critical()),
            MjAmount.ZERO, 0.0, null);
    }

    private static final class Core implements MachineComponent {
        final ResourceLocation typeId;
        EngineVisualState visual;
        boolean pulseDelivered;

        Core(ResourceLocation typeId, EngineVisualState visual) { this(typeId, visual, false); }
        Core(ResourceLocation typeId, EngineVisualState visual, boolean pulseDelivered) {
            this.typeId = typeId;
            this.visual = visual;
            this.pulseDelivered = pulseDelivered;
        }
        @Override public ResourceLocation typeId() { return typeId; }
    }

    private static PersistentType<Core, OpaqueData> corePersistence(ResourceLocation id) {
        return PersistentType.<Core, OpaqueData>builder(id, 1, new ApiCodec<>() {
            @Override public CodecResult<Core> decode(OpaqueData data) {
                try {
                    if (!STATE_FORMAT.equals(data.format())) throw new IOException("Unknown engine state format");
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(data.bytes()));
                    EngineVisualState visual = readVisual(in);
                    boolean delivered = in.readBoolean();
                    if (in.available() != 0) throw new IOException("Trailing engine state data");
                    return CodecResult.success(new Core(id, visual, delivered));
                } catch (IOException | IllegalArgumentException ex) {
                    return CodecResult.failure("Invalid engine state: " + ex.getMessage());
                }
            }
            @Override public CodecResult<OpaqueData> encode(Core core) {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bytes);
                    writeVisual(out, core.visual);
                    out.writeBoolean(core.pulseDelivered);
                    out.flush();
                    return CodecResult.success(new OpaqueData(STATE_FORMAT, bytes.toByteArray()));
                } catch (IOException ex) { return CodecResult.failure("Cannot encode engine state"); }
            }
        }).build();
    }

    private static PersistentType<EngineVisualState, OpaqueData> visualPersistence(ResourceLocation id) {
        return PersistentType.<EngineVisualState, OpaqueData>builder(id, 1, new ApiCodec<>() {
            @Override public CodecResult<EngineVisualState> decode(OpaqueData data) {
                try {
                    if (!VISUAL_FORMAT.equals(data.format())) throw new IOException("Unknown engine visual format");
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(data.bytes()));
                    EngineVisualState view = readVisual(in);
                    if (in.available() != 0) throw new IOException("Trailing engine visual data");
                    return CodecResult.success(view);
                } catch (IOException | IllegalArgumentException ex) {
                    return CodecResult.failure("Invalid engine visual: " + ex.getMessage());
                }
            }
            @Override public CodecResult<OpaqueData> encode(EngineVisualState view) {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    DataOutputStream out = new DataOutputStream(bytes);
                    writeVisual(out, view);
                    out.flush();
                    return CodecResult.success(new OpaqueData(VISUAL_FORMAT, bytes.toByteArray()));
                } catch (IOException ex) { return CodecResult.failure("Cannot encode engine visual"); }
            }
        }).build();
    }

    private static void writeVisual(DataOutputStream out, EngineVisualState state) throws IOException {
        ResourceLocation stage = state.stage().id();
        if (stage.toString().length() > MAX_STAGE_ID_LENGTH) throw new IOException("Stage ID too long");
        out.writeUTF(stage.toString());
        out.writeInt(state.stage().severity());
        out.writeDouble(state.heat().temperature());
        out.writeDouble(state.heat().criticalTemperature());
        out.writeLong(state.generatedLastTick().microMj());
        out.writeDouble(state.animationPhase());
        ResourceLocation sound = state.soundId();
        if (sound != null && sound.toString().length() > MAX_STAGE_ID_LENGTH) throw new IOException("Sound ID too long");
        out.writeUTF(sound == null ? "" : sound.toString());
    }

    private static EngineVisualState readVisual(DataInputStream in) throws IOException {
        String stageText = in.readUTF();
        if (stageText.length() > MAX_STAGE_ID_LENGTH) throw new IOException("Stage ID too long");
        ResourceLocation stage = ResourceLocation.tryParse(stageText);
        if (stage == null) throw new IOException("Invalid stage ID");
        int severity = in.readInt();
        double temperature = in.readDouble(), critical = in.readDouble();
        long generated = in.readLong();
        double phase = in.readDouble();
        String soundText = in.readUTF();
        if (soundText.length() > MAX_STAGE_ID_LENGTH) throw new IOException("Sound ID too long");
        ResourceLocation sound = soundText.isEmpty() ? null : ResourceLocation.tryParse(soundText);
        if (!soundText.isEmpty() && sound == null) throw new IOException("Invalid sound ID");
        return new EngineVisualState(new EngineStage(stage, severity), new HeatState(temperature, critical),
            MjAmount.ofMicro(generated), phase, sound);
    }

    private Core core() {
        return super.component(coreId, Core.class).orElse(null);
    }

    private void setCore(EngineVisualState next, boolean pulseDelivered) {
        Core previous = core();
        if (previous == null) throw new IllegalStateException("Engine state not initialized");
        if (previous.visual.equals(next) && previous.pulseDelivered == pulseDelivered) return;
        previous.visual = next;
        previous.pulseDelivered = pulseDelivered;
        markChanged();
    }

    @Override public EngineType engineType() { return engineType; }
    @Override public EngineHeatSettings heatSettings() { return heat; }
    @Override public MjPort outputBuffer() { return outputBuffer; }
    @Override public EngineVisualState visualState() {
        if (!isServerSide()) {
            return presentation(coreId, EngineVisualState.class).orElseGet(() -> idleState(heat));
        }
        Core current = core();
        return current == null ? idleState(heat) : current.visual;
    }
    @Override public EngineStage stage() { return visualState().stage(); }
    @Override public MjAmount outputPerTick() { return visualState().generatedLastTick(); }
    @Override public Optional<HeatPort> heatPort() { return Optional.of(heatPort); }
    @Override public Optional<MjPort> mjPort(Direction side) {
        return owner.mjPort(side).or(() -> Optional.of(publicOutput));
    }

    @Override public void setTemperature(double value) {
        serverActive();
        if (!Double.isFinite(value) || value < heat.operatingRange().minimum()) {
            throw new IllegalArgumentException("Engine temperature is invalid");
        }
        Core old = core();
        setCore(new EngineVisualState(old.visual.stage(), new HeatState(value, heat.critical()),
            old.visual.generatedLastTick(), old.visual.animationPhase(), old.visual.soundId()), old.pulseDelivered);
        if (value >= heat.critical()) {
            setWorkStatus(new WorkStatus(WorkState.BLOCKED, 0, "Engine overheated"));
        }
    }
    @Override public void setStage(EngineStage stage) {
        serverActive();
        Core old = core();
        setCore(new EngineVisualState(Objects.requireNonNull(stage), old.visual.heat(),
            old.visual.generatedLastTick(), old.visual.animationPhase(), old.visual.soundId()), old.pulseDelivered);
    }
    @Override public void setAnimation(double phase, ResourceLocation soundId) {
        serverActive();
        Core old = core();
        EngineVisualState next = new EngineVisualState(old.visual.stage(), old.visual.heat(),
            old.visual.generatedLastTick(), phase, soundId);
        setCore(next, phase < 0.5 ? false : old.pulseDelivered);
    }

    private void serverActive() {
        if (!isServerSide() || !isActive()) {
            throw new IllegalStateException("Engine mutations require an active server host");
        }
    }
    private void serverTick() {
        serverActive();
        if (!ticking) throw new IllegalStateException("MJ operations require an active engine tick");
    }

    @Override public MjTransferResult generate(MjAmount requested, OperationMode mode) {
        serverTick();
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(mode, "mode");
        if (visualState().heat().critical() || !outputBuffer.canInsert()) return MjTransferResult.none(requested);
        long rate = engineType.profile().maxOutputPerTick().microMj();
        long budget = Math.max(0, rate - producedThisTick);
        long available = Math.max(0, engineType.profile().capacity().microMj() - outputBuffer.stored().microMj());
        long count = Math.min(requested.microMj(), Math.min(budget, available));
        if (count == 0) return MjTransferResult.none(requested);
        long accepted = outputBuffer.insert(MjAmount.ofMicro(count), mode).transferred().microMj();
        if (accepted > count) throw new IllegalStateException("Engine buffer over-accepted MJ");
        if (mode == OperationMode.EXECUTE && accepted != 0) {
            producedThisTick += accepted;
            Core old = core();
            setCore(new EngineVisualState(old.visual.stage(), old.visual.heat(),
                MjAmount.ofMicro(producedThisTick), old.visual.animationPhase(), old.visual.soundId()), old.pulseDelivered);
        }
        return MjTransferResult.of(requested, MjAmount.ofMicro(accepted));
    }

    private long refundOutput(long amount) {
        if (amount <= 0) return 0;
        MjAmount due = MjAmount.ofMicro(amount);
        if (outputBuffer instanceof MjStorage storage) {
            long restored = storage.refundExtracted(due).microMj();
            if (restored == amount) return restored;
            // Custom storage implementations may not support transactional refund.
            amount -= restored;
        }
        return due.microMj() - amount
            + outputBuffer.insert(MjAmount.ofMicro(amount), OperationMode.EXECUTE).transferred().microMj();
    }

    @Override public MjTransferResult emit(Direction facing, MjAmount requested, OperationMode mode) {
        serverTick();
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(mode, "mode");
        Level level = owner.level();
        BlockPos target = owner.position().relative(facing);
        if (level == null || !level.hasChunkAt(target) || !outputBuffer.canExtract()) {
            return MjTransferResult.none(requested);
        }
        EnergyService energy = BuildCraftApi.service(BuildCraftServices.ENERGY);
        MjPort receiver = energy.port(level, target, facing.getOpposite()).orElse(null);
        if (receiver == null || receiver == outputBuffer || !receiver.canInsert()) {
            return MjTransferResult.none(requested);
        }
        Optional<MjPortDescriptor> localDesc = energy.descriptor(level, owner.position(), facing);
        Optional<MjPortDescriptor> remoteDesc = energy.descriptor(level, target, facing.getOpposite());
        if (localDesc.isEmpty() || remoteDesc.isEmpty()
            || !localDesc.get().has(MjPortRole.PROVIDER) || !remoteDesc.get().has(MjPortRole.CONSUMER)
            || !localDesc.get().networkId().equals(remoteDesc.get().networkId())
            || !energy.canConnect(new MjConnectionContext(level, owner.position(), facing, localDesc.get(), remoteDesc.get()))) {
            return MjTransferResult.none(requested);
        }
        boolean pulsed = remoteDesc.get().has(MjPortRole.REDSTONE_RECEIVER);
        Core state = core();
        if (pulsed && (state.visual.animationPhase() < 0.5 || state.pulseDelivered)) {
            return MjTransferResult.none(requested);
        }
        long budget = Math.max(0, engineType.profile().maxOutputPerTick().microMj() - sentThisTick);
        long candidate = Math.min(requested.microMj(), Math.min(budget, outputBuffer.stored().microMj()));
        candidate = Math.min(candidate, localDesc.get().maxExtractPerTick().microMj());
        candidate = Math.min(candidate, remoteDesc.get().maxInsertPerTick().microMj());
        if (candidate == 0) return MjTransferResult.none(requested);
        MjAmount requestedPort = MjAmount.ofMicro(candidate);
        long sourceAvailable = outputBuffer.extract(requestedPort, OperationMode.SIMULATE).transferred().microMj();
        if (sourceAvailable <= 0) return MjTransferResult.none(requested);
        long allowed = receiver.insert(MjAmount.ofMicro(sourceAvailable), OperationMode.SIMULATE).transferred().microMj();
        allowed = Math.min(allowed, sourceAvailable);
        if (allowed <= 0) return MjTransferResult.none(requested);
        if (mode == OperationMode.SIMULATE) return MjTransferResult.of(requested, MjAmount.ofMicro(allowed));
        long extracted = outputBuffer.extract(MjAmount.ofMicro(allowed), OperationMode.EXECUTE).transferred().microMj();
        if (extracted <= 0) return MjTransferResult.none(requested);
        long transferred;
        try {
            transferred = receiver.insert(MjAmount.ofMicro(extracted), OperationMode.EXECUTE).transferred().microMj();
        } catch (RuntimeException ex) {
            long refund = refundOutput(extracted);
            if (refund != extracted) {
                setWorkStatus(new WorkStatus(WorkState.ERROR, 0, "MJ refund failed"));
                throw new IllegalStateException("Failed to restore MJ after receiver exception", ex);
            }
            throw ex;
        }
        if (transferred > extracted) throw new IllegalStateException("Receiver over-accepted MJ");
        if (transferred < extracted) {
            long refund = refundOutput(extracted - transferred);
            if (refund != extracted - transferred) {
                setWorkStatus(new WorkStatus(WorkState.ERROR, 0, "MJ refund failed"));
                throw new IllegalStateException("MJ receiver changed capacity during transfer");
            }
        }
        if (transferred > 0) {
            sentThisTick += transferred;
            if (pulsed) setCore(state.visual, true);
            else markChanged();
        }
        return MjTransferResult.of(requested, MjAmount.ofMicro(transferred));
    }

    @Override public void onLoad() {
        super.onLoad();
        if (isActive()) {
            Core state = core();
            if (state != null && state.visual.heat().criticalTemperature() != heat.critical()) {
                EngineVisualState previous = state.visual;
                setCore(new EngineVisualState(previous.stage(),
                    new HeatState(previous.heat().temperature(), heat.critical()),
                    previous.generatedLastTick(), previous.animationPhase(), previous.soundId()),
                    state.pulseDelivered);
            }
        }
    }

    @Override public void tick() {
        if (!isServerSide() || owner.level() == null || !owner.level().hasChunkAt(owner.position())) return;
        producedThisTick = 0;
        sentThisTick = 0;
        onLoad();
        Core state = core();
        if (state != null && isActive()) {
            EngineVisualState previous = state.visual;
            double temperature = previous.heat().temperature();
            double cooled = Math.max(heat.ambient(), temperature - heat.passiveCoolingPerTick());
            if (cooled != temperature || !previous.generatedLastTick().isZero()) {
                EngineVisualState next = new EngineVisualState(previous.stage(),
                    new HeatState(cooled, heat.critical()), MjAmount.ZERO,
                    previous.animationPhase(), previous.soundId());
                setCore(next, state.pulseDelivered);
            }
        }
        ticking = true;
        try { super.tick(); }
        finally { ticking = false; }
        if (isActive() && visualState().heat().critical()
            && workStatus().state() != WorkState.ERROR && mode() != buildcraft.api.v2.machine.MachineControlMode.OFF) {
            setWorkStatus(new WorkStatus(WorkState.BLOCKED, 0, "Engine overheated"));
        }
    }
}
