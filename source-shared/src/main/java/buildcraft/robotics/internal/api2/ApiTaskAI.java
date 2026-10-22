package buildcraft.robotics.internal.api2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.persistence.CodecResult;
import buildcraft.api.v2.persistence.EncodedPayload;
import buildcraft.api.v2.persistence.KnownPayload;
import buildcraft.api.v2.persistence.OpaqueData;
import buildcraft.api.v2.persistence.PayloadResolution;
import buildcraft.api.v2.persistence.PersistentType;
import buildcraft.api.v2.robot.RobotResource;
import buildcraft.api.v2.robot.RobotResourceLease;
import buildcraft.api.v2.robot.RobotResourceRequest;
import buildcraft.api.v2.robot.RobotResourceType;
import buildcraft.api.v2.robot.RobotTask;
import buildcraft.api.v2.robot.RobotTaskContext;
import buildcraft.api.v2.robot.RobotTaskLifecycle;
import buildcraft.api.v2.robot.RobotTaskResult;
import buildcraft.api.v2.robot.RobotTaskType;
import buildcraft.lib.internal.api.v2.persistence.PersistenceRegistryBuilder;
import buildcraft.lib.internal.api.v2.persistence.PersistenceRegistrySnapshot;
import buildcraft.lib.internal.debug.BCLog;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;

/** Persistent adapter between registered API tasks and the classic robot AI executor. */
public final class ApiTaskAI extends AIRobot {
    private static final int MAGIC = 0x42435254;
    private static final int VERSION = 1;
    private static final int MAX_TASK_BYTES = 64 * 1024;
    private static final int MAX_RESOURCE_BYTES = 8 * 1024;
    private static final int MAX_RESOURCES = 16;
    private static final int MAX_FRAME_BYTES = 128 * 1024;
    private static final ThreadLocal<ApiTaskAI> EXECUTING = new ThreadLocal<>();

    private RobotTask task;
    private EncodedPayload<OpaqueData> snapshot;
    private byte[] originalFrame;
    private final List<TrackedLease> leases = new ArrayList<>();
    private final List<StoredLease> pendingLeases = new ArrayList<>();
    private RobotTaskLifecycle.Phase phase = RobotTaskLifecycle.Phase.QUEUED;
    private boolean restored;
    private boolean ended;
    private boolean unknownCleaned;

    /** Required by the classic AI loader; the task is supplied by loadSelfFromNBT. */
    public ApiTaskAI(EntityRobotBase robot) {
        super(robot);
    }

    public ApiTaskAI(EntityRobotBase robot, RobotTask task) {
        this(robot);
        this.task = Objects.requireNonNull(task, "task");
        snapshot = encodeTask(task).orElseThrow(() -> new IllegalArgumentException(
            "Unregistered or non-serializable robot task " + task.typeId()));
        saveFrame();
    }

    public static boolean canAssign(RobotTask task) {
        return task != null && encodeTask(task).isPresent();
    }

    public Optional<ResourceLocation> taskTypeId() {
        return Optional.ofNullable(snapshot == null ? null : snapshot.typeId());
    }

    public RobotTaskLifecycle.Phase phase() {
        return phase;
    }

    /** Called by the robotics service only for resource acquisitions inside task.tick. */
    static boolean trackIfExecuting(RobotResourceLease lease) {
        ApiTaskAI owner = EXECUTING.get();
        if (owner == null || owner.robot.getRobotId() != lease.robotId()) return true;
        Optional<EncodedPayload<OpaqueData>> encoded = encodeResource(lease.resource());
        if (encoded.isEmpty() || owner.leases.size() >= MAX_RESOURCES) {
            lease.close();
            return false;
        }
        owner.leases.add(new TrackedLease(lease, new StoredLease(encoded.get(), lease.amount())));
        return true;
    }

    private static Level robotLevel(EntityRobotBase robot) {
        //? if <1.20 {
        /*?
        return robot.level;
        ?*/
        //?} else {
        return robot.level();
        //?}
    }

    private RobotTaskContext context() {
        return new RobotTaskContext(robotLevel(robot), RobotServiceImpl.view(robot), RobotServiceImpl.actorForTask(robot));
    }

    @Override
    public int getEnergyCost() {
        return task == null || ended || phase == RobotTaskLifecycle.Phase.SUSPENDED ? 0 : 1;
    }

    @Override
    public boolean canLoadFromNBT() { return true; }

    @Override
    public void start() {
        if (task == null || ended) return;
        phase = RobotTaskLifecycle.Phase.ASSIGNED;
        notifyTask(RobotTaskLifecycle.Phase.ASSIGNED);
    }

    @Override
    public void update() {
        if (ended || !(robotLevel(robot) instanceof ServerLevel)) return;
        if (task == null) {
            cleanupUnknownResources();
            return;
        }
        if (restored) {
            if (!reacquireLeases()) {
                suspend();
                return;
            }
            restored = false;
            phase = RobotTaskLifecycle.Phase.ASSIGNED;
            notifyTask(RobotTaskLifecycle.Phase.SUSPENDED); // resume after durable state restoration
        }
        if (phase == RobotTaskLifecycle.Phase.SUSPENDED) {
            if (!reacquireLeases()) return;
            phase = RobotTaskLifecycle.Phase.ASSIGNED;
            notifyTask(RobotTaskLifecycle.Phase.SUSPENDED);
        }
        if (ended) return;
        phase = RobotTaskLifecycle.Phase.RUNNING;
        RobotTaskResult result;
        try {
            if (EXECUTING.get() != null) throw new IllegalStateException("Nested robot task execution");
            EXECUTING.set(this);
            result = Objects.requireNonNull(task.tick(context()), "Task returned null");
        } catch (RuntimeException ex) {
            BCLog.logger.warn("API robot task failed: " + task.typeId(), ex);
            phase = RobotTaskLifecycle.Phase.FAILED;
            setSuccess(false);
            terminate();
            return;
        } finally {
            EXECUTING.remove();
        }
        switch (result.status()) {
            case RUNNING, RETRY -> { }
            case COMPLETE -> { phase = RobotTaskLifecycle.Phase.COMPLETED; terminate(); }
            case FAILED -> {
                phase = RobotTaskLifecycle.Phase.FAILED;
                setSuccess(false);
                terminate();
            }
        }
    }

    private void suspend() {
        if (phase == RobotTaskLifecycle.Phase.SUSPENDED) return;
        phase = RobotTaskLifecycle.Phase.SUSPENDED;
        if (task instanceof RobotTaskLifecycle lifecycle) {
            ApiTaskAI previous = EXECUTING.get();
            try {
                EXECUTING.set(this);
                lifecycle.onSuspended(context());
            } catch (RuntimeException ex) {
                BCLog.logger.warn("API robot task suspension failed", ex);
            } finally {
                if (previous == null) EXECUTING.remove();
                else EXECUTING.set(previous);
            }
        }
    }

    private void notifyTask(RobotTaskLifecycle.Phase event) {
        if (!(task instanceof RobotTaskLifecycle lifecycle)) return;
        ApiTaskAI previous = EXECUTING.get();
        try {
            EXECUTING.set(this);
            RobotTaskContext ctx = context();
            switch (event) {
                case ASSIGNED -> lifecycle.onAssigned(ctx);
                case SUSPENDED -> lifecycle.onResumed(ctx);
                case COMPLETED -> lifecycle.onCompleted(ctx);
                case FAILED -> lifecycle.onFailed(ctx);
                case CANCELLED -> lifecycle.onCancelled(ctx);
                default -> { }
            }
        } catch (RuntimeException ex) {
            BCLog.logger.warn("API robot task callback failed: " + event, ex);
        } finally {
            if (previous == null) EXECUTING.remove();
            else EXECUTING.set(previous);
        }
    }

    @Override
    public void end() {
        if (ended) return;
        ended = true;
        if (phase != RobotTaskLifecycle.Phase.COMPLETED && phase != RobotTaskLifecycle.Phase.FAILED) {
            phase = RobotTaskLifecycle.Phase.CANCELLED;
        }
        notifyTask(phase);
        for (TrackedLease entry : leases) {
            try { entry.lease.close(); }
            catch (RuntimeException ex) { BCLog.logger.warn("Robot task lease release failed", ex); }
        }
        leases.clear();
        releasePendingResources();
    }

    /** Suspend execution and release runtime leases when the robot leaves the loaded world. */
    public void suspendForUnload() {
        if (ended) return;
        if (task != null) suspend();
        for (TrackedLease entry : leases) {
            if (!entry.lease.active()) continue;
            pendingLeases.add(entry.saved);
            try { entry.lease.close(); }
            catch (RuntimeException ex) { BCLog.logger.warn("Robot task unload release failed", ex); }
        }
        leases.clear();
        restored = true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        byte[] frame = saveFrame();
        if (frame.length != 0) nbt.putByteArray("task", frame);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        task = null;
        snapshot = null;
        originalFrame = null;
        leases.clear();
        pendingLeases.clear();
        ended = false;
        unknownCleaned = false;
        restored = true;
        phase = RobotTaskLifecycle.Phase.SUSPENDED;
        if (!(nbt.get("task") instanceof ByteArrayTag binary)) return;
        byte[] raw = binary.getAsByteArray();
        if (raw.length > MAX_FRAME_BYTES) return;
        originalFrame = raw.clone();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) return;
            int storedPhase = in.readUnsignedByte();
            if (storedPhase >= RobotTaskLifecycle.Phase.values().length) return;
            snapshot = readPayload(in, MAX_TASK_BYTES);
            int count = in.readUnsignedByte();
            if (count > MAX_RESOURCES) { snapshot = null; return; }
            for (int i = 0; i < count; i++) {
                long amount = in.readLong();
                if (amount <= 0) { snapshot = null; return; }
                pendingLeases.add(new StoredLease(readPayload(in, MAX_RESOURCE_BYTES), amount));
            }
            if (in.available() != 0) { snapshot = null; pendingLeases.clear(); return; }
            Optional<RobotTask> decoded = decodeTask(snapshot);
            if (decoded.isPresent() && storedPhase < RobotTaskLifecycle.Phase.COMPLETED.ordinal()) {
                task = decoded.get();
            }
        } catch (IOException | RuntimeException ex) {
            BCLog.logger.warn("Cannot decode saved API robot task; preserving raw payload", ex);
            snapshot = null;
            pendingLeases.clear();
        }
    }

    private byte[] saveFrame() {
        if (originalFrame != null && (task == null || snapshot == null)) return originalFrame.clone();
        if (task == null || snapshot == null) return new byte[0];
        Optional<EncodedPayload<OpaqueData>> encoded = encodeTask(task);
        if (encoded.isEmpty()) return originalFrame == null ? new byte[0] : originalFrame.clone();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(phase.ordinal());
            writePayload(out, encoded.get(), MAX_TASK_BYTES);
            List<StoredLease> active = new ArrayList<>(pendingLeases);
            for (TrackedLease lease : leases) if (lease.lease.active()) active.add(lease.saved);
            if (active.size() > MAX_RESOURCES) throw new IOException("Too many robot task resources");
            out.writeByte(active.size());
            for (StoredLease lease : active) {
                out.writeLong(lease.amount);
                writePayload(out, lease.data, MAX_RESOURCE_BYTES);
            }
            out.flush();
            if (bytes.size() > MAX_FRAME_BYTES) throw new IOException("Robot task state exceeds limit");
            originalFrame = bytes.toByteArray();
            snapshot = encoded.get();
            return originalFrame.clone();
        } catch (IOException ex) {
            BCLog.logger.warn("Could not persist API robot task; keeping prior checkpoint", ex);
            return originalFrame == null ? new byte[0] : originalFrame.clone();
        }
    }

    private boolean reacquireLeases() {
        if (pendingLeases.isEmpty()) return true;
        List<TrackedLease> acquired = new ArrayList<>();
        for (StoredLease saved : pendingLeases) {
            Optional<RobotResource> resource = decodeResource(saved.data);
            if (resource.isEmpty()) { close(acquired); return false; }
            Optional<RobotResourceLease> lease = BuildCraftApi.service(BuildCraftServices.ROBOTS)
                .acquire(robotLevel(robot), robot.getRobotId(), new RobotResourceRequest(resource.get(), saved.amount));
            if (lease.isEmpty()) { close(acquired); return false; }
            acquired.add(new TrackedLease(lease.get(), saved));
        }
        pendingLeases.clear();
        leases.addAll(acquired);
        return true;
    }

    private void cleanupUnknownResources() {
        if (unknownCleaned || task != null) return;
        unknownCleaned = true;
        releasePendingResources();
    }

    private void releasePendingResources() {
        for (StoredLease saved : pendingLeases) {
            decodeResource(saved.data).ifPresent(resource -> {
                try {
                    BuildCraftApi.service(BuildCraftServices.ROBOTS)
                        .acquire(robotLevel(robot), robot.getRobotId(), new RobotResourceRequest(resource, saved.amount))
                        .ifPresent(RobotResourceLease::close);
                } catch (RuntimeException ex) {
                    BCLog.logger.warn("Could not release orphan robot resource", ex);
                }
            });
        }
    }

    private static void close(List<TrackedLease> leases) {
        for (TrackedLease entry : leases) entry.lease.close();
    }

    private static Optional<EncodedPayload<OpaqueData>> encodeTask(RobotTask value) {
        RobotTaskType<?> type = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_TASK_TYPES).get(value.typeId());
        if (type == null || !type.taskType().isInstance(value)) return Optional.empty();
        CodecResult<EncodedPayload<OpaqueData>> result = taskRegistry().encode(type.id(), value);
        return result.successful() && result.valueOrThrow().payload().size() <= MAX_TASK_BYTES
            ? Optional.of(result.valueOrThrow()) : Optional.empty();
    }

    private static Optional<RobotTask> decodeTask(EncodedPayload<OpaqueData> encoded) {
        PayloadResolution<RobotTask, OpaqueData> result = taskRegistry().decode(encoded, data ->
            new OpaqueData(data.format(), data.bytes()));
        if (!(result instanceof KnownPayload<RobotTask, OpaqueData> known)) return Optional.empty();
        RobotTaskType<?> registered = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_TASK_TYPES).get(known.canonicalTypeId());
        return registered != null && registered.taskType().isInstance(known.value())
            && registered.id().equals(known.value().typeId()) ? Optional.of(known.value()) : Optional.empty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PersistenceRegistrySnapshot<RobotTask, OpaqueData> taskRegistry() {
        PersistenceRegistryBuilder<RobotTask, OpaqueData> builder = new PersistenceRegistryBuilder<>();
        for (RobotTaskType<?> type : BuildCraftApi.registry(BuildCraftRegistries.ROBOT_TASK_TYPES).values()) {
            builder.register((PersistentType) type.persistence(), () -> "buildcraftrobotics");
        }
        return builder.freeze();
    }

    private static Optional<EncodedPayload<OpaqueData>> encodeResource(RobotResource value) {
        RobotResourceType<?> type = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_RESOURCE_TYPES).get(value.typeId());
        if (type == null || !type.resourceType().isInstance(value)) return Optional.empty();
        CodecResult<EncodedPayload<OpaqueData>> result = resourceRegistry().encode(type.id(), value);
        return result.successful() && result.valueOrThrow().payload().size() <= MAX_RESOURCE_BYTES
            ? Optional.of(result.valueOrThrow()) : Optional.empty();
    }

    private static Optional<RobotResource> decodeResource(EncodedPayload<OpaqueData> encoded) {
        PayloadResolution<RobotResource, OpaqueData> result = resourceRegistry().decode(encoded, data ->
            new OpaqueData(data.format(), data.bytes()));
        if (!(result instanceof KnownPayload<RobotResource, OpaqueData> known)) return Optional.empty();
        RobotResourceType<?> registered = BuildCraftApi.registry(BuildCraftRegistries.ROBOT_RESOURCE_TYPES).get(known.canonicalTypeId());
        return registered != null && registered.resourceType().isInstance(known.value())
            ? Optional.of(known.value()) : Optional.empty();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static PersistenceRegistrySnapshot<RobotResource, OpaqueData> resourceRegistry() {
        PersistenceRegistryBuilder<RobotResource, OpaqueData> builder = new PersistenceRegistryBuilder<>();
        for (RobotResourceType<?> type : BuildCraftApi.registry(BuildCraftRegistries.ROBOT_RESOURCE_TYPES).values()) {
            builder.register((PersistentType) type.persistence(), () -> "buildcraftrobotics");
        }
        return builder.freeze();
    }

    private static void writePayload(DataOutputStream out, EncodedPayload<OpaqueData> value, int limit) throws IOException {
        if (value.payload().size() > limit) throw new IOException("Oversized extension state");
        out.writeUTF(value.typeId().toString());
        out.writeInt(value.schemaVersion());
        out.writeUTF(value.payload().format().toString());
        byte[] bytes = value.payload().bytes();
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static EncodedPayload<OpaqueData> readPayload(DataInputStream in, int limit) throws IOException {
        ResourceLocation type = parseId(in.readUTF());
        int version = in.readInt();
        ResourceLocation format = parseId(in.readUTF());
        int length = in.readInt();
        if (version < 0 || length < 0 || length > limit || length > in.available()) throw new IOException("Invalid extension envelope");
        return new EncodedPayload<>(type, version, new OpaqueData(format, in.readNBytes(length)));
    }

    private static ResourceLocation parseId(String raw) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || raw.length() > 256) throw new IOException("Invalid resource location");
        return id;
    }

    private record StoredLease(EncodedPayload<OpaqueData> data, long amount) { }
    private record TrackedLease(RobotResourceLease lease, StoredLease saved) { }
}
