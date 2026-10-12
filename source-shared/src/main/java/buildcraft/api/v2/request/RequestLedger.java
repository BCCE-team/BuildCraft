package buildcraft.api.v2.request;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.item.ItemTransferResult;
import buildcraft.api.v2.permission.ActorType;
import buildcraft.api.v2.permission.AutomationActor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Per-requester accounting for demand, outstanding promises, and accepted deliveries.
 * Does not schedule robots, move items, or maintain a second inventory. A request
 * definition (including its non-serializable matcher) must be registered again after
 * {@link #load(byte[])}; unbound requests and reservations remain persisted but inert.
 * All operations, including persistence, run on the owner's server thread.
 */
public final class RequestLedger {
    private static final int MAGIC = 0x42435251;
    private static final int FORMAT = 1;
    private static final int MAX_DATA = 1024 * 1024;
    private static final int MAX_REQUESTS = 512;
    private static final int MAX_PROMISES = 2048;
    private static final int MAX_RECEIPTS = 4096;
    private static final int MAX_TEXT = 256;

    public enum Phase { OPEN, COMPLETE, CANCELLED, TIMED_OUT }
    public enum PromisePhase { ACTIVE, COMPLETE, RELEASED, CANCELLED, TIMED_OUT }
    public enum DeliveryStatus { MOVED, PARTIAL, SIMULATED, DUPLICATE, BLOCKED, UNAVAILABLE, DENIED, EXPIRED, CLOSED, FAILED }

    public record RequestView(ResourceLocation id, int minimum, int maximum, int priority,
                              int confirmed, int reserved, Phase phase, long deadline, String diagnostic) {
        public int available() { return Math.max(0, maximum - confirmed - reserved); }
        public int outstanding() { return Math.max(0, maximum - confirmed); }
    }

    public record PromiseView(UUID id, ResourceLocation requestId, int promised, int confirmed,
                              PromisePhase phase, long deadline) {
        public int remaining() { return Math.max(0, promised - confirmed); }
    }

    /** accepted is newly inserted; acknowledged is a prior receipt or a simulated acceptance. */
    public record DeliveryResult(DeliveryStatus status, int accepted, int acknowledged, int remaining, String diagnostic) {
        public DeliveryResult {
            Objects.requireNonNull(status, "status");
            if (accepted < 0 || acknowledged < 0 || remaining < 0) throw new IllegalArgumentException("negative count");
        }
        public static DeliveryResult failure(DeliveryStatus status, String detail) {
            return failure(status, 0, detail);
        }
        public static DeliveryResult failure(DeliveryStatus status, int offered, String detail) {
            return new DeliveryResult(status, 0, 0, Math.max(0, offered), detail);
        }
    }

    private static final class Entry {
        final ResourceLocation id;
        final int minimum, maximum, priority;
        final AutomationActor owner;
        final long deadline;
        ItemRequest definition;
        int confirmed;
        Phase phase;
        String diagnostic;

        Entry(ItemRequest request, AutomationActor owner, long deadline) {
            this(request.id(), request.minimum(), request.maximum(), request.priority(), owner, deadline,
                0, Phase.OPEN, "");
            definition = request;
        }

        Entry(ResourceLocation id, int minimum, int maximum, int priority, AutomationActor owner,
              long deadline, int confirmed, Phase phase, String diagnostic) {
            this.id = id;
            this.minimum = minimum;
            this.maximum = maximum;
            this.priority = priority;
            this.owner = owner;
            this.deadline = deadline;
            this.confirmed = confirmed;
            this.phase = phase;
            this.diagnostic = diagnostic;
        }
    }

    private static final class Promise {
        final UUID id;
        final Entry request;
        final AutomationActor actor;
        final int promised;
        final long deadline;
        int confirmed;
        PromisePhase phase;

        Promise(UUID id, Entry request, AutomationActor actor, int promised, long deadline,
                int confirmed, PromisePhase phase) {
            this.id = id;
            this.request = request;
            this.actor = actor;
            this.promised = promised;
            this.deadline = deadline;
            this.confirmed = confirmed;
            this.phase = phase;
        }
    }

    private record Receipt(UUID promise, int count) {}

    private final Thread owner = Thread.currentThread();
    private final Runnable onChange;

    public RequestLedger() { this(() -> {}); }

    /** The callback should mark the enclosing block entity dirty and must not mutate this ledger. */
    public RequestLedger(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    private void changed() {
        // A failed dirty-mark callback must never convert an already accepted item transfer
        // into a failed response that the sender may retry.
        try { onChange.run(); } catch (RuntimeException ignored) { }
    }
    private final Map<ResourceLocation, Entry> requests = new LinkedHashMap<>();
    private final Map<UUID, Promise> promises = new LinkedHashMap<>();
    private final Map<UUID, Receipt> receipts = new LinkedHashMap<>();
    private long gameTime;
    private byte[] unrecognizedData;

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Request ledger accessed off its owning thread");
    }

    private void writable() {
        checkThread();
        if (unrecognizedData != null) throw new IllegalStateException("Unrecognized ledger data must be preserved, not overwritten");
    }

    /** Rebinds a saved request by stable ID, or opens a new request. Duplicate opens are idempotent. */
    public RequestView open(ItemRequest definition, AutomationActor ownerActor, long now, long durationTicks) {
        writable();
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(ownerActor, "ownerActor");
        advance(now);
        Entry existing = requests.get(definition.id());
        if (existing != null) {
            if (!sameDefinition(existing, definition) || !existing.owner.equals(ownerActor)) {
                throw new IllegalArgumentException("Conflicting request ID: " + definition.id());
            }
            existing.definition = definition;
            return view(existing);
        }
        if (requests.size() >= MAX_REQUESTS) throw new IllegalStateException("request limit reached");
        Entry added = new Entry(definition, ownerActor, deadline(now, durationTicks));
        if (added.maximum == 0) {
            added.phase = Phase.COMPLETE;
            added.diagnostic = "fulfilled";
        }
        requests.put(added.id, added);
        changed();
        return view(added);
    }

    private static boolean sameDefinition(Entry entry, ItemRequest def) {
        return entry.minimum == def.minimum() && entry.maximum == def.maximum() && entry.priority == def.priority();
    }

    /** Ready requests exclude quantity already promised to another courier. */
    public Collection<ItemRequest> requests() {
        checkThread();
        List<ItemRequest> result = new ArrayList<>();
        for (Entry entry : requests.values()) {
            int available = available(entry);
            if (entry.definition != null && entry.phase == Phase.OPEN && available > 0) {
                result.add(new ItemRequest(entry.id, entry.definition.matcher(),
                    Math.min(entry.minimum, available), available, entry.priority));
            }
        }
        result.sort(Comparator.comparingInt(ItemRequest::priority).reversed().thenComparing(r -> r.id().toString()));
        return List.copyOf(result);
    }

    public Optional<RequestView> request(ResourceLocation id) {
        checkThread();
        Entry entry = requests.get(id);
        return entry == null ? Optional.empty() : Optional.of(view(entry));
    }

    public Collection<RequestView> snapshots() {
        checkThread();
        return List.copyOf(requests.values().stream().map(this::view).toList());
    }

    public Optional<PromiseView> promise(UUID id) {
        checkThread();
        Promise entry = promises.get(id);
        return entry == null ? Optional.empty() : Optional.of(view(entry));
    }

    /** Reserve up to available demand. Reusing a token returns its existing promise. */
    public Optional<PromiseView> reserve(ResourceLocation requestId, UUID token, int count,
                                         AutomationActor actor, long now, long durationTicks) {
        writable();
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(actor, "actor");
        if (count <= 0) throw new IllegalArgumentException("count must be positive");
        advance(now);
        Promise previous = promises.get(token);
        if (previous != null) {
            if (!previous.request.id.equals(requestId) || !previous.actor.equals(actor) || previous.promised != count) {
                throw new IllegalArgumentException("Reservation ID already belongs to another promise");
            }
            return Optional.of(view(previous));
        }
        Entry request = requests.get(requestId);
        if (request == null || request.phase != Phase.OPEN || request.definition == null || available(request) < count) {
            return Optional.empty();
        }
        if (promises.size() >= MAX_PROMISES) return Optional.empty();
        long until = Math.min(request.deadline, deadline(now, durationTicks));
        if (until <= now) return Optional.empty();
        Promise promise = new Promise(token, request, actor, count, until, 0, PromisePhase.ACTIVE);
        promises.put(token, promise);
        changed();
        return Optional.of(view(promise));
    }

    /**
     * Deliver using the provider's authoritative inventory/pipe insertion method.
     * A delivery ID must be unique for each attempt that might insert items; replay of an
     * acknowledged ID never invokes the provider a second time. Source inventories must
     * debit only `accepted` of a MOVED/PARTIAL result, not a DUPLICATE result.
     */
    public DeliveryResult deliver(ManagedRequestProvider provider, UUID token, UUID deliveryId,
                                  ItemStack offered, AutomationActor actor, OperationMode mode) {
        writable();
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(offered, "offered");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(mode, "mode");
        advance(provider.requestTime());
        Promise promise = promises.get(token);
        if (promise == null) return DeliveryResult.failure(DeliveryStatus.UNAVAILABLE, offered.getCount(), "unknown_reservation");
        if (!promise.actor.equals(actor)) return DeliveryResult.failure(DeliveryStatus.DENIED, offered.getCount(), "actor_mismatch");
        Receipt old = receipts.get(deliveryId);
        if (old != null) {
            if (!old.promise.equals(token)) return DeliveryResult.failure(DeliveryStatus.DENIED, offered.getCount(), "delivery_id_reused");
            return new DeliveryResult(DeliveryStatus.DUPLICATE, 0, old.count, offered.getCount(), "already_acknowledged");
        }
        if (promise.phase == PromisePhase.TIMED_OUT) {
            return DeliveryResult.failure(DeliveryStatus.EXPIRED, offered.getCount(), "reservation_expired");
        }
        if (promise.phase != PromisePhase.ACTIVE) {
            return DeliveryResult.failure(DeliveryStatus.CLOSED, offered.getCount(), "reservation_closed");
        }
        if (promise.request.phase == Phase.TIMED_OUT) {
            return DeliveryResult.failure(DeliveryStatus.EXPIRED, offered.getCount(), "request_expired");
        }
        if (promise.request.phase != Phase.OPEN) {
            return DeliveryResult.failure(DeliveryStatus.CLOSED, offered.getCount(), "request_closed");
        }
        if (gameTime >= promise.deadline) {
            promise.phase = PromisePhase.TIMED_OUT;
            return DeliveryResult.failure(DeliveryStatus.EXPIRED, offered.getCount(), "reservation_expired");
        }
        Entry entry = promise.request;
        if (entry.definition == null) return DeliveryResult.failure(DeliveryStatus.UNAVAILABLE, offered.getCount(), "request_not_bound");
        try {
            if (offered.isEmpty() || !entry.definition.matcher().matches(offered)) {
                return DeliveryResult.failure(DeliveryStatus.BLOCKED, offered.getCount(), "item_mismatch");
            }
        } catch (RuntimeException matcherError) {
            return DeliveryResult.failure(DeliveryStatus.FAILED, offered.getCount(), "matcher_exception");
        }
        int wanted = Math.min(offered.getCount(), promise.promised - promise.confirmed);
        if (wanted <= 0) return DeliveryResult.failure(DeliveryStatus.CLOSED, offered.getCount(), "promise_fulfilled");
        if (mode == OperationMode.EXECUTE && receipts.size() >= MAX_RECEIPTS) {
            return DeliveryResult.failure(DeliveryStatus.BLOCKED, offered.getCount(), "receipt_limit");
        }
        ItemStack bounded = offered.copy();
        bounded.setCount(wanted);
        try {
            ItemTransferResult result = Objects.requireNonNull(provider.insertRequested(entry.id, bounded.copy(), mode), "offer result");
            int accepted = result.transferredCount();
            if (accepted < 0 || accepted > wanted || !validTransfer(bounded, result)) {
                if (mode == OperationMode.EXECUTE) closeUncertain(entry, "invalid_provider_result");
                return DeliveryResult.failure(DeliveryStatus.FAILED, offered.getCount(), "invalid_provider_result");
            }
            if (mode == OperationMode.SIMULATE) {
                return new DeliveryResult(DeliveryStatus.SIMULATED, 0, accepted, offered.getCount() - accepted, "");
            }
            if (accepted == 0) return new DeliveryResult(DeliveryStatus.BLOCKED, 0, 0, offered.getCount(), "destination_full");
            promise.confirmed += accepted;
            entry.confirmed += accepted;
            receipts.put(deliveryId, new Receipt(token, accepted));
            if (promise.confirmed == promise.promised) promise.phase = PromisePhase.COMPLETE;
            finishIfComplete(entry);
            changed();
            return new DeliveryResult(accepted == offered.getCount() ? DeliveryStatus.MOVED : DeliveryStatus.PARTIAL,
                accepted, 0, offered.getCount() - accepted, "");
        } catch (RuntimeException failure) {
            if (mode == OperationMode.EXECUTE) closeUncertain(entry, "provider_exception");
            return DeliveryResult.failure(DeliveryStatus.FAILED, offered.getCount(), "provider_exception");
        }
    }

    /** Legacy robot delivery path: count actual insertion, but do not consume other robots' promises. */
    public ItemTransferResult offerUnreserved(ManagedRequestProvider provider, ResourceLocation id,
                                               ItemStack offered, OperationMode mode) {
        writable();
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(offered, "offered");
        Objects.requireNonNull(mode, "mode");
        Entry entry = requests.get(id);
        int count = offered.getCount();
        if (entry == null || entry.phase != Phase.OPEN || entry.definition == null || offered.isEmpty()
            || available(entry) <= 0) {
            return ItemTransferResult.nothing(Math.max(0, count));
        }
        try {
            if (!entry.definition.matcher().matches(offered)) return ItemTransferResult.nothing(Math.max(0, count));
        } catch (RuntimeException matcherError) {
            return ItemTransferResult.nothing(Math.max(0, count));
        }
        ItemStack bounded = offered.copy();
        bounded.setCount(Math.min(count, available(entry)));
        try {
            ItemTransferResult result = Objects.requireNonNull(provider.insertRequested(id, bounded.copy(), mode));
            if (!validTransfer(bounded, result)) {
                if (mode == OperationMode.EXECUTE) closeUncertain(entry, "invalid_provider_result");
                return ItemTransferResult.nothing(Math.max(0, count));
            }
            if (mode == OperationMode.EXECUTE) {
                entry.confirmed += result.transferredCount();
                finishIfComplete(entry);
                if (result.transferredCount() > 0) changed();
            }
            return ItemTransferResult.ofInsertion(offered, result.transferredCount());
        } catch (RuntimeException failure) {
            if (mode == OperationMode.EXECUTE) closeUncertain(entry, "provider_exception");
            return ItemTransferResult.nothing(Math.max(0, count));
        }
    }

    /** Cancel demand and release all remaining promises; only the creator may cancel. */
    public boolean cancel(ResourceLocation id, AutomationActor actor, String reason) {
        writable();
        Entry entry = requests.get(id);
        if (entry == null || entry.phase != Phase.OPEN || !entry.owner.equals(actor)) return false;
        entry.phase = Phase.CANCELLED;
        entry.diagnostic = boundedReason(reason);
        closePromises(entry, PromisePhase.CANCELLED);
        changed();
        return true;
    }

    /** Release unused reserved quantity; only its courier or request creator may release it. */
    public boolean release(UUID token, AutomationActor actor) {
        writable();
        Promise promise = promises.get(token);
        if (promise == null || promise.phase != PromisePhase.ACTIVE
            || !(promise.actor.equals(actor) || promise.request.owner.equals(actor))) return false;
        promise.phase = PromisePhase.RELEASED;
        changed();
        return true;
    }

    /** Expire old requests and leases without loading chunks or making world changes. */
    public void advance(long now) {
        checkThread();
        if (unrecognizedData != null) return;
        if (now < 0) throw new IllegalArgumentException("negative game time");
        gameTime = Math.max(gameTime, now);
        boolean expired = false;
        for (Entry entry : requests.values()) {
            if (entry.phase == Phase.OPEN && gameTime >= entry.deadline) {
                entry.phase = Phase.TIMED_OUT;
                entry.diagnostic = "request_timeout";
                closePromises(entry, PromisePhase.TIMED_OUT);
                expired = true;
            }
        }
        for (Promise promise : promises.values()) {
            if (promise.phase == PromisePhase.ACTIVE && gameTime >= promise.deadline) {
                promise.phase = PromisePhase.TIMED_OUT;
                expired = true;
            }
        }
        if (expired) changed();
    }

    public long gameTime() { checkThread(); return gameTime; }

    /**
     * Encode only bounded accounting data. ItemMatcher is deliberately not serialized:
     * the providing addon must rebind its definition on load before any delivery.
     */
    public byte[] save() {
        checkThread();
        if (unrecognizedData != null) return unrecognizedData.clone();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            out.writeLong(gameTime);
            out.writeInt(requests.size());
            for (Entry entry : requests.values()) {
                writeId(out, entry.id);
                out.writeInt(entry.minimum);
                out.writeInt(entry.maximum);
                out.writeInt(entry.priority);
                writeActor(out, entry.owner);
                out.writeLong(entry.deadline);
                out.writeInt(entry.confirmed);
                out.writeByte(entry.phase.ordinal());
                out.writeUTF(boundedReason(entry.diagnostic));
            }
            out.writeInt(promises.size());
            for (Promise promise : promises.values()) {
                writeUuid(out, promise.id);
                writeId(out, promise.request.id);
                writeActor(out, promise.actor);
                out.writeInt(promise.promised);
                out.writeInt(promise.confirmed);
                out.writeLong(promise.deadline);
                out.writeByte(promise.phase.ordinal());
            }
            out.writeInt(receipts.size());
            for (Map.Entry<UUID, Receipt> receipt : receipts.entrySet()) {
                writeUuid(out, receipt.getKey());
                writeUuid(out, receipt.getValue().promise);
                out.writeInt(receipt.getValue().count);
            }
            out.flush();
            if (bytes.size() > MAX_DATA) throw new IllegalStateException("request ledger exceeds persistence limit");
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("Cannot encode request ledger", error);
        }
    }

    /** Reject corrupted or future formats without changing the currently loaded ledger. */
    public void load(byte[] bytes) throws IOException {
        checkThread();
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_DATA) throw new IOException("request ledger is too large");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IOException("unknown request ledger format");
        long tick = in.readLong();
        if (tick < 0) throw new IOException("invalid game time");
        Map<ResourceLocation, Entry> readRequests = new LinkedHashMap<>();
        int size = checkedSize(in.readInt(), MAX_REQUESTS);
        for (int i = 0; i < size; i++) {
            ResourceLocation id = readId(in);
            int minimum = in.readInt(), maximum = in.readInt(), priority = in.readInt();
            if (minimum < 0 || maximum < minimum) throw new IOException("invalid request quantity");
            AutomationActor actor = readActor(in);
            long deadline = in.readLong();
            int confirmed = in.readInt();
            Phase phase = readEnum(in, Phase.values());
            String reason = in.readUTF();
            if (reason.length() > MAX_TEXT || deadline < 0 || confirmed < 0 || confirmed > maximum) {
                throw new IOException("invalid request data");
            }
            Entry entry = new Entry(id, minimum, maximum, priority, actor, deadline, confirmed, phase, reason);
            if (readRequests.putIfAbsent(id, entry) != null) throw new IOException("duplicate request ID");
        }
        Map<UUID, Promise> readPromises = new LinkedHashMap<>();
        size = checkedSize(in.readInt(), MAX_PROMISES);
        for (int i = 0; i < size; i++) {
            UUID token = readUuid(in);
            Entry entry = readRequests.get(readId(in));
            AutomationActor actor = readActor(in);
            int promised = in.readInt(), confirmed = in.readInt();
            long deadline = in.readLong();
            PromisePhase phase = readEnum(in, PromisePhase.values());
            if (entry == null || promised <= 0 || confirmed < 0 || confirmed > promised || deadline < 0) {
                throw new IOException("invalid reservation data");
            }
            Promise promise = new Promise(token, entry, actor, promised, deadline, confirmed, phase);
            if (readPromises.putIfAbsent(token, promise) != null) throw new IOException("duplicate promise ID");
        }
        Map<UUID, Receipt> readReceipts = new LinkedHashMap<>();
        size = checkedSize(in.readInt(), MAX_RECEIPTS);
        for (int i = 0; i < size; i++) {
            UUID receiptId = readUuid(in), token = readUuid(in);
            int count = in.readInt();
            if (!readPromises.containsKey(token) || count <= 0 ||
                readReceipts.putIfAbsent(receiptId, new Receipt(token, count)) != null) {
                throw new IOException("invalid delivery receipt");
            }
        }
        if (in.available() != 0) throw new IOException("trailing request ledger data");
        for (Entry entry : readRequests.values()) {
            if (entry.phase == Phase.OPEN && activeCount(readPromises.values(), entry) > (long) entry.maximum - entry.confirmed) {
                throw new IOException("overcommitted request");
            }
            if (entry.phase != Phase.OPEN && activeCount(readPromises.values(), entry) != 0) {
                throw new IOException("active reservation for closed request");
            }
        }
        Map<UUID, Long> acknowledged = new LinkedHashMap<>();
        for (Receipt receipt : readReceipts.values()) {
            acknowledged.merge(receipt.promise, (long) receipt.count, Long::sum);
        }
        for (Promise promise : readPromises.values()) {
            if (acknowledged.getOrDefault(promise.id, 0L) > promise.confirmed) {
                throw new IOException("receipt total exceeds confirmed count");
            }
        }
        // Definitions intentionally remain null until the addon binds its matchers.
        requests.clear();
        requests.putAll(readRequests);
        promises.clear();
        promises.putAll(readPromises);
        receipts.clear();
        receipts.putAll(readReceipts);
        gameTime = tick;
        unrecognizedData = null;
    }

    /** Preserve an unsupported/corrupt bounded payload unchanged and prohibit writes until it can be decoded. */
    public boolean loadPreserving(byte[] bytes) {
        checkThread();
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_DATA) throw new IllegalArgumentException("request ledger exceeds persistence limit");
        if (!requests.isEmpty()) throw new IllegalStateException("Load before opening requests");
        try {
            load(bytes);
            return true;
        } catch (IOException | RuntimeException failure) {
            unrecognizedData = bytes.clone();
            return false;
        }
    }

    private static int checkedSize(int n, int max) throws IOException {
        if (n < 0 || n > max) throw new IOException("invalid entry count");
        return n;
    }

    private static <E> E readEnum(DataInputStream in, E[] choices) throws IOException {
        int index = in.readUnsignedByte();
        if (index >= choices.length) throw new IOException("invalid state");
        return choices[index];
    }

    private static void writeId(DataOutputStream out, ResourceLocation id) throws IOException {
        if (id.toString().length() > MAX_TEXT) throw new IOException("ID is too long");
        out.writeUTF(id.toString());
    }

    private static ResourceLocation readId(DataInputStream in) throws IOException {
        String value = in.readUTF();
        ResourceLocation id = value.length() <= MAX_TEXT ? ResourceLocation.tryParse(value) : null;
        if (id == null) throw new IOException("invalid identifier");
        return id;
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeActor(DataOutputStream out, AutomationActor actor) throws IOException {
        out.writeByte(actor.type().ordinal());
        out.writeBoolean(actor.playerId().isPresent());
        if (actor.playerId().isPresent()) writeUuid(out, actor.playerId().get());
        String name = actor.playerName().orElse("");
        out.writeUTF(boundedReason(name));
        out.writeBoolean(actor.sourceId().isPresent());
        if (actor.sourceId().isPresent()) writeId(out, actor.sourceId().get());
    }

    private static AutomationActor readActor(DataInputStream in) throws IOException {
        ActorType type = readEnum(in, ActorType.values());
        UUID player = in.readBoolean() ? readUuid(in) : null;
        String name = in.readUTF();
        if (name.length() > MAX_TEXT) throw new IOException("actor name too long");
        ResourceLocation source = in.readBoolean() ? readId(in) : null;
        if (type == ActorType.PLAYER && player != null && source == null) return AutomationActor.player(player, name);
        if (type == ActorType.MACHINE_OWNER && player != null && source != null) {
            return AutomationActor.machineOwner(player, name, source);
        }
        if (type == ActorType.SYSTEM && source != null && player == null) return AutomationActor.system(source);
        if (type == ActorType.UNKNOWN && source == null && player == null) return AutomationActor.unknown();
        throw new IOException("invalid actor");
    }

    private static String boundedReason(String reason) {
        if (reason == null) return "";
        return reason.length() <= MAX_TEXT ? reason : reason.substring(0, MAX_TEXT);
    }

    private static long deadline(long now, long duration) {
        if (duration < 0) throw new IllegalArgumentException("negative duration");
        if (duration == 0 || now > Long.MAX_VALUE - duration) return Long.MAX_VALUE;
        return now + duration;
    }

    private static boolean validTransfer(ItemStack offered, ItemTransferResult result) {
        if (result.requestedCount() != offered.getCount() || result.transferredCount() > offered.getCount()) return false;
        ItemStack transferred = result.transferred();
        if (transferred.isEmpty()) return true;
        ItemStack expected = offered.copy();
        expected.setCount(1);
        ItemStack actual = transferred.copy();
        actual.setCount(1);
        return ItemStack.matches(expected, actual);
    }

    private static long activeCount(Collection<Promise> values, Entry entry) {
        long result = 0;
        for (Promise promise : values) {
            if (promise.request == entry && promise.phase == PromisePhase.ACTIVE) {
                result += promise.promised - promise.confirmed;
            }
        }
        return result;
    }

    private int available(Entry entry) {
        return (int) Math.max(0L, (long) entry.maximum - entry.confirmed - activeCount(promises.values(), entry));
    }

    private RequestView view(Entry entry) {
        return new RequestView(entry.id, entry.minimum, entry.maximum, entry.priority, entry.confirmed,
            (int) activeCount(promises.values(), entry), entry.phase, entry.deadline, entry.diagnostic);
    }

    private static PromiseView view(Promise promise) {
        return new PromiseView(promise.id, promise.request.id, promise.promised, promise.confirmed,
            promise.phase, promise.deadline);
    }

    private void finishIfComplete(Entry entry) {
        if (entry.confirmed == entry.maximum) {
            entry.phase = Phase.COMPLETE;
            entry.diagnostic = "fulfilled";
            closePromises(entry, PromisePhase.RELEASED);
        }
    }

    private void closePromises(Entry entry, PromisePhase phase) {
        for (Promise promise : promises.values()) {
            if (promise.request == entry && promise.phase == PromisePhase.ACTIVE) promise.phase = phase;
        }
    }

    private void closeUncertain(Entry entry, String reason) {
        entry.phase = Phase.CANCELLED;
        entry.diagnostic = reason;
        closePromises(entry, PromisePhase.CANCELLED);
        changed();
    }

    /** Forget a terminal request and its receipts after the owner has reconciled any delivery. */
    public boolean forget(ResourceLocation id, AutomationActor actor) {
        writable();
        Entry entry = requests.get(id);
        if (entry == null || entry.phase == Phase.OPEN || !entry.owner.equals(actor)) return false;
        requests.remove(id);
        java.util.Set<UUID> removed = new java.util.HashSet<>();
        promises.values().removeIf(promise -> {
            if (promise.request != entry) return false;
            removed.add(promise.id);
            return true;
        });
        receipts.values().removeIf(receipt -> removed.contains(receipt.promise));
        changed();
        return true;
    }
}
