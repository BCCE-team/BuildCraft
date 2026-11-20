package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Main-thread-only, per-dimension claims for dropped-item pickup jobs.
 *
 * An ItemEntity UUID is one useful job, irrespective of stack count. Claims are
 * deliberately transient: after reload the world entities are rediscovered and
 * validated, never replayed from old numeric entity IDs or saved inventories.
 * The token prevents a delayed completion from releasing a newer assignment.
 */
public final class RobotPickupQueue {
    public record Claim(UUID itemUuid, long panelId, long robotId, long token,
                        boolean active, long expiresAt) { }

    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private long nextToken = 1L;

    public Claim claim(UUID item, long panel, long robot, int capacity, long now, long ttl) {
        if (item == null || robot <= 0 || capacity < 1 || capacity > 8 || ttl <= 0) return null;
        Claim existing = claims.get(item);
        if (existing != null) {
            return existing.expiresAt() > now && existing.panelId() == panel
                    && existing.robotId() == robot ? existing : null;
        }
        if (size(panel, robot) >= capacity) return null;
        long token = nextToken++;
        if (token <= 0) { nextToken = 2L; token = 1L; }
        Claim created = new Claim(item, panel, robot, token, false, now + ttl);
        claims.put(item, created);
        return created;
    }

    public Claim activate(UUID item, long panel, long robot, long now, long ttl) {
        Claim old = claims.get(item);
        if (old == null || old.panelId() != panel || old.robotId() != robot
                || old.expiresAt() <= now || ttl <= 0) return null;
        Claim active = new Claim(item, panel, robot, old.token(), true, now + ttl);
        claims.put(item, active);
        return active;
    }

    public boolean renew(UUID item, long panel, long robot, long token, long now, long ttl) {
        Claim old = claims.get(item);
        if (old == null || old.panelId() != panel || old.robotId() != robot
                || old.token() != token || old.expiresAt() <= now || ttl <= 0) return false;
        claims.put(item, new Claim(item, panel, robot, token, old.active(), now + ttl));
        return true;
    }

    public boolean release(UUID item, long robot, long token) {
        Claim old = claims.get(item);
        if (old == null || old.robotId() != robot || old.token() != token) return false;
        claims.remove(item);
        return true;
    }

    public boolean isClaimedByOther(UUID item, long robot, long now) {
        Claim claim = claims.get(item);
        return claim != null && claim.expiresAt() > now && claim.robotId() != robot;
    }

    public List<Claim> jobs(long panel, long robot) {
        List<Claim> jobs = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.panelId() == panel && claim.robotId() == robot) jobs.add(claim);
        }
        return jobs;
    }

    public int size(long panel, long robot) {
        int count = 0;
        for (Claim claim : claims.values()) {
            if (claim.panelId() == panel && claim.robotId() == robot) count++;
        }
        return count;
    }

    /** Preserve an in-flight pickup when the player reduces the queue limit. */
    public void trim(long panel, long robot, int capacity) {
        int limit = Math.max(0, Math.min(8, capacity));
        List<Claim> jobs = jobs(panel, robot);
        for (int i = jobs.size() - 1; i >= 0 && jobs.size() > limit; i--) {
            Claim job = jobs.get(i);
            if (job.active()) continue;
            claims.remove(job.itemUuid());
            jobs.remove(i);
        }
    }

    public void clearRobot(long panel, long robot) {
        claims.values().removeIf(claim -> claim.panelId() == panel && claim.robotId() == robot);
    }

    public void clearPanel(long panel) {
        claims.values().removeIf(claim -> claim.panelId() == panel);
    }

    public void expire(long now) {
        claims.values().removeIf(claim -> claim.expiresAt() <= now);
    }
}
