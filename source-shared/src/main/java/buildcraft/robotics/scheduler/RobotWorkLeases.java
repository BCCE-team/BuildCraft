package buildcraft.robotics.scheduler;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-level, temporary work hints. This is NOT the authoritative resource registry:
 * after a hint the original AI must still take its ResourceIdBlock and verify rights.
 * Panel IDs are packed BlockPos values within the containing level.
 */
public final class RobotWorkLeases {
    private record Claim(long robotId, long panelId, long expiryTick, long token) { }
    private final Map<Long, Claim> claims = new HashMap<>();
    private long nextToken = 1L;

    public boolean available(long packedTarget, long robotId, long panelId, long now) {
        Claim claim = claims.get(packedTarget);
        return claim == null || now >= claim.expiryTick
                || (claim.robotId == robotId && claim.panelId == panelId);
    }

    /** Atomically claim a candidate without creating a permanent reservation. */
    public long claim(long packedTarget, long robotId, long panelId, long now, long duration) {
        if (robotId <= 0 || duration <= 0 || !available(packedTarget, robotId, panelId, now)) return 0L;
        long token = nextToken++;
        if (token <= 0L) { nextToken = 2L; token = 1L; }
        claims.put(packedTarget, new Claim(robotId, panelId, now + duration, token));
        return token;
    }

    /** Extend only the exact current reservation, without changing its generation. */
    public boolean renew(long packedTarget, long robotId, long panelId, long token, long now, long duration) {
        Claim claim = claims.get(packedTarget);
        if (claim == null || claim.robotId != robotId || claim.panelId != panelId
                || claim.token != token || now >= claim.expiryTick || duration <= 0) return false;
        claims.put(packedTarget, new Claim(robotId, panelId, now + duration, token));
        return true;
    }

    public boolean tryClaim(long packedTarget, long robotId, long panelId, long now, long duration) {
        return claim(packedTarget, robotId, panelId, now, duration) != 0L;
    }

    /** A delayed release from a different robot must never erase a newer claim. */
    public void release(long packedTarget, long robotId, long token) {
        Claim claim = claims.get(packedTarget);
        if (claim != null && claim.robotId == robotId && claim.token == token) claims.remove(packedTarget);
    }

    public void releasePanel(long panelId) {
        claims.values().removeIf(claim -> claim.panelId == panelId);
    }

    public void expire(long now) {
        claims.values().removeIf(claim -> now >= claim.expiryTick);
    }

    public int size() { return claims.size(); }
}
