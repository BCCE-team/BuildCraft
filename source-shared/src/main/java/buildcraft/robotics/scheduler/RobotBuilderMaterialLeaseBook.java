package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Bounded, server-thread-only promises against the CURRENT stock of one source.
 * The lease never removes items. Only the native load AI commits transfers, and
 * a source modified by another mod can always supply less than was promised.
 *
 * <p>Source and material keys are value objects, not inventories, tiles or worlds.
 * Nothing in this ledger can keep an unloaded chunk or world alive.</p>
 */
public final class RobotBuilderMaterialLeaseBook<S, M> {
    public record Lease<S, M>(S source, M material, long panelId, long robotId,
                               long token, int quantity, long expiresAt) { }

    private static final int MAX_LEASES = 512;
    private final Map<Long, Lease<S, M>> byRobot = new HashMap<>();
    private long nextToken = 1;

    public void expire(long now) {
        byRobot.values().removeIf(lease -> lease.expiresAt() <= now);
    }

    public int reserved(S source, M material, long now) {
        expire(now);
        long total = 0;
        for (Lease<S, M> lease : byRobot.values()) {
            if (lease.source().equals(source) && lease.material().equals(material)) {
                total += lease.quantity();
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public int available(S source, M material, int physicalStock, long now) {
        return (int) Math.max(0L, (long) Math.max(0, physicalStock) - reserved(source, material, now));
    }

    /** Exactly one pending material transfer per robot. Idempotency is token-based. */
    public Lease<S, M> reserve(S source, M material, long panelId, long robotId,
                                int physicalStock, int wanted, long now, long duration) {
        if (source == null || material == null || robotId <= 0 || wanted <= 0 || duration <= 0) return null;
        expire(now);
        if (byRobot.containsKey(robotId) || byRobot.size() >= MAX_LEASES) return null;
        int quantity = Math.min(wanted, available(source, material, physicalStock, now));
        if (quantity <= 0) return null;
        long token = nextToken++;
        if (token <= 0) { nextToken = 2; token = 1; }
        Lease<S, M> lease = new Lease<>(source, material, panelId, robotId, token,
                quantity, now + Math.min(duration, Long.MAX_VALUE - now));
        byRobot.put(robotId, lease);
        return lease;
    }

    public boolean owns(long robotId, long token, long now) {
        Lease<S, M> lease = byRobot.get(robotId);
        return lease != null && lease.token() == token && lease.expiresAt() > now;
    }

    public boolean ownsAt(long robotId, long token, S source, long now) {
        Lease<S, M> lease = byRobot.get(robotId);
        return lease != null && lease.token() == token && lease.source().equals(source)
                && lease.expiresAt() > now;
    }

    public boolean release(long robotId, long token) {
        Lease<S, M> lease = byRobot.get(robotId);
        if (lease == null || lease.token() != token) return false;
        return byRobot.remove(robotId, lease);
    }

    public void releaseRobot(long robotId) { byRobot.remove(robotId); }

    public void releasePanel(long panelId) {
        for (Lease<S, M> lease : new ArrayList<>(byRobot.values())) {
            if (lease.panelId() == panelId) release(lease.robotId(), lease.token());
        }
    }

    public int size() { return byRobot.size(); }
}
