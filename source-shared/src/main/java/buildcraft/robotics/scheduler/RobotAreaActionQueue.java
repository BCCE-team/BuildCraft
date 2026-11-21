package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ephemeral, server-thread-only area-action reservations. Stripes reserves a
 * single position; TNT reserves the *blast footprint*, so two processors may
 * never dispatch overlapping live explosives. No world references or NBT.
 */
public final class RobotAreaActionQueue {
    public enum Kind { STRIPES, BOMBER }
    public record Key(Kind kind, long pos) { }
    public record Claim(Key key, long panel, long robot, long token, boolean active, long expiresAt) { }
    private static final int MAX_RECENT = 4096;
    private static final int BLAST_SEPARATION = 12; // Two conservative radius-six footprints.

    private final Map<Key, Claim> claims = new LinkedHashMap<>();
    private final Map<Key, Long> recentlyUsed = new LinkedHashMap<>();
    private long nextToken = 1L;

    private static int x(long pos) { return (int) (pos >> 38); }
    private static int y(long pos) { return (int) (pos << 52 >> 52); }
    private static int z(long pos) { return (int) (pos << 26 >> 38); }

    private static boolean conflicts(Key a, Key b) {
        if (a.kind() != b.kind()) return false;
        if (a.kind() == Kind.STRIPES) return a.pos() == b.pos();
        long dx = (long) x(a.pos()) - x(b.pos());
        long dz = (long) z(a.pos()) - z(b.pos());
        long dy = (long) y(a.pos()) - y(b.pos());
        // Do not stack primed TNT in intersecting conservative blast spheres.
        return dx * dx + dy * dy + dz * dz <= (long) BLAST_SEPARATION * BLAST_SEPARATION;
    }

    public boolean available(Kind kind, long pos, long now) {
        Key request = new Key(kind, pos);
        for (Claim claim : claims.values()) {
            if (claim.expiresAt() > now && conflicts(request, claim.key())) return false;
        }
        for (Map.Entry<Key, Long> entry : recentlyUsed.entrySet()) {
            if (entry.getValue() > now && conflicts(request, entry.getKey())) return false;
        }
        return true;
    }

    public Claim reserve(Kind kind, long pos, long panel, long robot, int capacity, long now, long ttl) {
        if (kind == null || robot <= 0 || capacity <= 0 || ttl <= 0 || !available(kind, pos, now)
                || count(kind, panel, robot) >= capacity) return null;
        long token = nextToken++;
        if (token <= 0) { token = 1L; nextToken = 2L; }
        Claim claim = new Claim(new Key(kind, pos), panel, robot, token, false, now + ttl);
        claims.put(claim.key(), claim);
        return claim;
    }

    public Claim activate(Kind kind, long pos, long panel, long robot, long now, long ttl) {
        Key key = new Key(kind, pos);
        Claim claim = claims.get(key);
        if (claim == null || claim.expiresAt() <= now || claim.panel() != panel || claim.robot() != robot)
            return null;
        Claim active = new Claim(key, panel, robot, claim.token(), true, now + ttl);
        claims.put(key, active);
        return active;
    }

    public boolean renew(Claim claim, long now, long ttl) {
        Claim current = claims.get(claim.key());
        if (current == null || current.expiresAt() <= now || current.panel() != claim.panel()
                || current.robot() != claim.robot() || current.token() != claim.token()) return false;
        claims.put(claim.key(), new Claim(current.key(), current.panel(), current.robot(),
                current.token(), current.active(), now + ttl));
        return true;
    }

    public boolean owns(Kind kind, long pos, long robot, long token, long now) {
        Claim claim = claims.get(new Key(kind, pos));
        return claim != null && claim.active() && claim.robot() == robot
                && claim.token() == token && claim.expiresAt() > now;
    }

    public boolean release(Kind kind, long pos, long robot, long token, boolean completed, long now) {
        Key key = new Key(kind, pos);
        Claim claim = claims.get(key);
        if (claim == null || claim.robot() != robot || claim.token() != token) return false;
        claims.remove(key);
        if (completed) {
            recentlyUsed.put(key, now + (kind == Kind.BOMBER ? 200L : 80L));
            while (recentlyUsed.size() > MAX_RECENT) {
                Iterator<Key> it = recentlyUsed.keySet().iterator();
                it.next(); it.remove();
            }
        }
        return true;
    }

    public List<Claim> jobs(Kind kind, long panel, long robot) {
        List<Claim> result = new ArrayList<>();
        for (Claim claim : claims.values()) {
            if (claim.key().kind() == kind && claim.panel() == panel && claim.robot() == robot)
                result.add(claim);
        }
        return result;
    }

    public int count(Kind kind, long panel, long robot) {
        int result = 0;
        for (Claim claim : claims.values()) {
            if (claim.key().kind() == kind && claim.panel() == panel && claim.robot() == robot) result++;
        }
        return result;
    }

    public void trim(Kind kind, long panel, long robot, int capacity) {
        int excess = count(kind, panel, robot) - Math.max(0, capacity);
        if (excess <= 0) return;
        List<Claim> jobs = jobs(kind, panel, robot);
        for (int i = jobs.size() - 1; i >= 0 && excess > 0; i--) {
            if (!jobs.get(i).active()) { claims.remove(jobs.get(i).key()); excess--; }
        }
    }

    public void clearPanel(long panel) {
        claims.values().removeIf(claim -> claim.panel() == panel);
    }

    public void clearRobot(long panel, long robot) {
        claims.values().removeIf(claim -> claim.panel() == panel && claim.robot() == robot);
    }

    public void expire(long now) {
        claims.values().removeIf(claim -> claim.expiresAt() <= now);
        recentlyUsed.values().removeIf(until -> until <= now);
    }
}
