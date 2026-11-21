package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-thread-only reservations of *live* combat entities. UUIDs are not
 * persistent tasks: respawned mobs are new work, even at the same location.
 *
 * Fencing tokens protect newer claims against stale attack completion. Claims
 * are shared between panels of the same level, not between dimensions.
 */
public final class RobotCombatTargetQueue {
    public record Claim(UUID entity, long panel, long robot, long token, boolean active, long expiresAt) { }

    private final Map<UUID, Claim> claims = new LinkedHashMap<>();
    private long nextToken = 1L;

    public Claim reserve(UUID entity, long panel, long robot, int capacity, long now, long ttl) {
        if (entity == null || capacity <= 0 || ttl <= 0 || robot <= 0) return null;
        Claim old = claims.get(entity);
        if (old != null && old.expiresAt() <= now) {
            claims.remove(entity);
            old = null;
        }
        if (old != null) {
            return old.panel() == panel && old.robot() == robot ? old : null;
        }
        if (count(panel, robot) >= capacity) return null;
        long token = nextToken++;
        if (token <= 0L) { token = 1L; nextToken = 2L; }
        Claim job = new Claim(entity, panel, robot, token, false, now + ttl);
        claims.put(entity, job);
        return job;
    }

    public Claim activate(UUID entity, long panel, long robot, long now, long ttl) {
        Claim old = claims.get(entity);
        if (old == null || old.panel() != panel || old.robot() != robot || old.expiresAt() <= now) return null;
        Claim active = new Claim(entity, panel, robot, old.token(), true, now + ttl);
        claims.put(entity, active);
        return active;
    }

    public boolean renew(UUID entity, long panel, long robot, long token, long now, long ttl) {
        Claim old = claims.get(entity);
        if (old == null || old.expiresAt() <= now || old.panel() != panel
                || old.robot() != robot || old.token() != token) return false;
        claims.put(entity, new Claim(entity, panel, robot, token, old.active(), now + ttl));
        return true;
    }

    public boolean isReserved(UUID entity, long now) {
        Claim old = claims.get(entity);
        return old != null && old.expiresAt() > now;
    }

    public boolean release(UUID entity, long robot, long token) {
        Claim old = claims.get(entity);
        if (old == null || old.robot() != robot || old.token() != token) return false;
        claims.remove(entity);
        return true;
    }

    public int count(long panel, long robot) {
        int count = 0;
        for (Claim job : claims.values()) if (job.panel() == panel && job.robot() == robot) count++;
        return count;
    }

    public List<Claim> jobs(long panel, long robot) {
        List<Claim> jobs = new ArrayList<>();
        for (Claim job : claims.values()) {
            if (job.panel() == panel && job.robot() == robot) jobs.add(job);
        }
        return jobs;
    }

    public void expire(long now) {
        claims.values().removeIf(job -> job.expiresAt() <= now);
    }

    public void clearRobot(long panel, long robot) {
        claims.values().removeIf(job -> job.panel() == panel && job.robot() == robot);
    }

    public void clearPanel(long panel) {
        claims.values().removeIf(job -> job.panel() == panel);
    }

    /** Queue shrinking must never discard a target currently being attacked. */
    public void trim(long panel, long robot, int maximum) {
        int excess = count(panel, robot) - Math.max(0, maximum);
        if (excess <= 0) return;
        // Pending claims are ordered by discovery; retain older jobs first.
        List<Claim> jobs = jobs(panel, robot);
        for (int i = jobs.size() - 1; i >= 0 && excess > 0; i--) {
            Claim job = jobs.get(i);
            if (!job.active()) {
                claims.remove(job.entity());
                excess--;
            }
        }
    }
}
