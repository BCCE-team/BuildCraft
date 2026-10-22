package buildcraft.transport.api2;

import java.util.UUID;
import buildcraft.api.v2.pipe.ItemTransitPayload;

/** Server-thread-only ownership handoff between adjacent vanilla item flows. */
public final class PipeTransitHandoff implements AutoCloseable {
    private static final ThreadLocal<PipeTransitHandoff> CURRENT = new ThreadLocal<>();
    private final PipeTransitHandoff previous;
    private UUID token;
    private ItemTransitPayload payload;
    private boolean closed;

    private PipeTransitHandoff(UUID token, ItemTransitPayload payload) {
        this.payload = payload;
        this.previous = CURRENT.get();
        this.token = token;
        CURRENT.set(this);
    }

    /** A null token deliberately starts a fresh untracked insertion scope. */
    public static PipeTransitHandoff begin(UUID token) {
        return new PipeTransitHandoff(token, ItemTransitPayload.EMPTY);
    }

    public static PipeTransitHandoff begin(UUID token, ItemTransitPayload payload) {
        return new PipeTransitHandoff(token, java.util.Objects.requireNonNull(payload, "payload"));
    }

    public record Envelope(UUID id, ItemTransitPayload payload) { }

    /** Claim both the token and the payload exactly once from the active insertion scope. */
    public static Envelope claim() {
        PipeTransitHandoff scope = CURRENT.get();
        if (scope == null || scope.closed) return new Envelope(null, ItemTransitPayload.EMPTY);
        Envelope claimed = new Envelope(scope.token, scope.payload);
        scope.token = null;
        scope.payload = ItemTransitPayload.EMPTY;
        return claimed;
    }

    /** Claim a token exactly once, even if a destination splits the inserted stack. */
    public static UUID take() {
        PipeTransitHandoff scope = CURRENT.get();
        if (scope == null || scope.closed) return null;
        return claim().id();
    }

    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Out-of-order pipe transit scope");
        closed = true;
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
