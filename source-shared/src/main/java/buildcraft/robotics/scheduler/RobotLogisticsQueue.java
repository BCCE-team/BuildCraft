package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-thread-only, generation-fenced advisory logistics reservations.
 *
 * <p>A key represents either one source dock (item/fluid transport) or one
 * request at a destination dock (delivery). The actual inventory/fluid transfer
 * remains the responsibility of the legacy robot AI; reserving a key never
 * extracts an item or a fluid. This value-only core has no world references.</p>
 */
public final class RobotLogisticsQueue {
    public enum Kind { ITEM_SOURCE, FLUID_SOURCE, ITEM_REQUEST }

    /** Side is the 3D direction index; a request ID is only used by ITEM_REQUEST. */
    public record Key(Kind kind, long stationPos, int side, String requestId) {
        public Key {
            if (kind == null) throw new IllegalArgumentException("kind");
            requestId = requestId == null ? "" : requestId;
        }
    }

    public record Claim(Key key, long panelId, long robotId, long token,
                        boolean active, long expiresAt) { }

    private final Map<Key, Claim> claims = new LinkedHashMap<>();
    private long nextToken = 1L;

    public Claim claim(Key key, long panelId, long robotId, int capacity, long now, long ttl) {
        if (key == null || robotId <= 0 || capacity < 1 || capacity > 8 || ttl <= 0) return null;
        Claim existing = claims.get(key);
        if (existing != null && existing.expiresAt() > now) {
            return existing.panelId() == panelId && existing.robotId() == robotId ? existing : null;
        }
        if (size(panelId, robotId) >= capacity) return null;
        long token = nextToken++;
        if (token <= 0) { nextToken = 2L; token = 1L; }
        Claim created = new Claim(key, panelId, robotId, token, false, now + ttl);
        claims.put(key, created);
        return created;
    }

    public Claim activate(Key key, long panelId, long robotId, long now, long ttl) {
        Claim old = claims.get(key);
        if (old == null || old.panelId() != panelId || old.robotId() != robotId
                || old.expiresAt() <= now || ttl <= 0) return null;
        Claim active = new Claim(key, panelId, robotId, old.token(), true, now + ttl);
        claims.put(key, active);
        return active;
    }

    public boolean renew(Key key, long panelId, long robotId, long token, long now, long ttl) {
        Claim old = claims.get(key);
        if (old == null || old.panelId() != panelId || old.robotId() != robotId
                || old.token() != token || old.expiresAt() <= now || ttl <= 0) return false;
        claims.put(key, new Claim(key, panelId, robotId, token, old.active(), now + ttl));
        return true;
    }

    public Claim get(Key key, long now) {
        Claim claim = claims.get(key);
        return claim != null && claim.expiresAt() > now ? claim : null;
    }

    public boolean isTakenByOther(Key key, long robotId, long now) {
        Claim existing = get(key, now);
        return existing != null && existing.robotId() != robotId;
    }

    public boolean release(Key key, long robotId, long token) {
        Claim old = claims.get(key);
        if (old == null || old.robotId() != robotId || old.token() != token) return false;
        claims.remove(key);
        return true;
    }

    public List<Claim> jobs(long panelId, long robotId) {
        List<Claim> jobs = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.panelId() == panelId && claim.robotId() == robotId) jobs.add(claim);
        }
        return jobs;
    }

    public int size(long panelId, long robotId) {
        int count = 0;
        for (Claim claim : claims.values()) {
            if (claim.panelId() == panelId && claim.robotId() == robotId) count++;
        }
        return count;
    }

    public void trim(long panelId, long robotId, int capacity) {
        int limit = Math.max(0, Math.min(8, capacity));
        List<Claim> assigned = jobs(panelId, robotId);
        for (int i = assigned.size() - 1; i >= 0 && assigned.size() > limit; i--) {
            Claim job = assigned.get(i);
            if (!job.active()) {
                claims.remove(job.key());
                assigned.remove(i);
            }
        }
    }

    public void clearRobot(long panelId, long robotId) {
        claims.values().removeIf(claim -> claim.panelId() == panelId && claim.robotId() == robotId);
    }

    public void clearPanel(long panelId) {
        claims.values().removeIf(claim -> claim.panelId() == panelId);
    }

    public void expire(long now) {
        claims.values().removeIf(claim -> claim.expiresAt() <= now);
    }
}
