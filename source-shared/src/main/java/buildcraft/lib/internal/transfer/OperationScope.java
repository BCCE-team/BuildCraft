package buildcraft.lib.internal.transfer;

import buildcraft.api.v2.OperationMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One logical transfer operation shared across nested BuildCraft/platform adapters.
 *
 * <p>The scope intentionally stays internal. Forge/NeoForge use it as a recursion and
 * simulation boundary today; a Fabric adapter may attach its native transaction object
 * to the shared scope so a multi-hop transfer keeps one transaction owner instead of
 * opening unrelated transactions at every adapter boundary.</p>
 */
public final class OperationScope implements AutoCloseable {
    private static final ThreadLocal<Deque<OperationScope>> STACK =
        ThreadLocal.withInitial(ArrayDeque::new);

    private final RootState root;
    private final OperationScope parent;
    private final OperationMode mode;
    private final Thread owner;
    private boolean closed;

    private OperationScope(OperationMode mode, OperationScope parent) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.parent = parent;
        this.root = parent == null ? new RootState() : parent.root;
        this.owner = Thread.currentThread();
    }

    public static OperationScope open(OperationMode mode) {
        Deque<OperationScope> stack = STACK.get();
        OperationScope scope = new OperationScope(mode, stack.peek());
        stack.push(scope);
        return scope;
    }

    public static Optional<OperationScope> current() {
        return Optional.ofNullable(STACK.get().peek());
    }

    public OperationMode mode() {
        return mode;
    }

    public boolean simulate() {
        return mode == OperationMode.SIMULATE;
    }

    public Optional<OperationScope> parent() {
        return Optional.ofNullable(parent);
    }

    /**
     * Prevents an endpoint from being re-entered through an adapter loop.
     * A blocked guard is still closeable and simply reports {@link Guard#entered()} false.
     */
    public Guard enter(Object endpointIdentity) {
        requireOpen();
        Objects.requireNonNull(endpointIdentity, "endpointIdentity");
        if (root.active.containsKey(endpointIdentity)) {
            return new Guard(this, endpointIdentity, false);
        }
        root.active.put(endpointIdentity, Boolean.TRUE);
        return new Guard(this, endpointIdentity, true);
    }

    /**
     * Finds an attachment shared by every nested scope in this logical operation.
     * Platform adapters may use this for native transaction/journal ownership.
     */
    public <T> Optional<T> sharedAttachment(Object key, Class<T> type) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Object value = root.attachments.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }

    /** Creates one shared attachment lazily for the whole logical operation. */
    public <T> T sharedAttachment(Object key, Supplier<? extends T> factory) {
        requireOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        Object existing = root.attachments.get(key);
        if (existing != null) {
            @SuppressWarnings("unchecked")
            T cast = (T) existing;
            return cast;
        }
        T created = Objects.requireNonNull(factory.get(), "attachment");
        root.attachments.put(key, created);
        return created;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Operation scope is already closed");
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Operation scopes are thread-confined");
        }
    }

    @Override
    public void close() {
        if (closed) return;
        requireOpen();
        Deque<OperationScope> stack = STACK.get();
        if (stack.peek() != this) {
            throw new IllegalStateException("Operation scopes must close in LIFO order");
        }
        stack.pop();
        closed = true;
        if (stack.isEmpty()) {
            STACK.remove();
            root.active.clear();
            root.attachments.clear();
        }
    }

    private static final class RootState {
        final IdentityHashMap<Object, Boolean> active = new IdentityHashMap<>();
        final IdentityHashMap<Object, Object> attachments = new IdentityHashMap<>();
    }

    public static final class Guard implements AutoCloseable {
        private final OperationScope scope;
        private final Object identity;
        private final boolean entered;
        private boolean closed;

        private Guard(OperationScope scope, Object identity, boolean entered) {
            this.scope = scope;
            this.identity = identity;
            this.entered = entered;
        }

        public boolean entered() {
            return entered;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (entered) {
                scope.requireOpen();
                scope.root.active.remove(identity);
            }
        }
    }
}
