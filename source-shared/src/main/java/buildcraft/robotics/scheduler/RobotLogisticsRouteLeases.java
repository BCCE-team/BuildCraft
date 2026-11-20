package buildcraft.robotics.scheduler;

import java.util.HashMap;
import java.util.Map;

/**
 * A lightweight, server-thread-only advisory reservation of an unloading dock.
 * A lease never takes a physical station or reserves space in a target inventory.
 * Only the native docking and unload AI may commit a transfer. Its generation
 * token prevents an old robot AI from releasing a newer assignment.
 */
public final class RobotLogisticsRouteLeases {
    public record Dock(long position, int side) { }
    public record Claim(Dock dock, long panelId, long robotId, long token, long expiresAt) { }

    private final Map<Dock, Claim> byDock = new HashMap<>();
    private final Map<Long, Claim> byRobot = new HashMap<>();
    private long nextToken = 1L;

    public Claim reserve(Dock dock, long panelId, long robotId, long now, long duration) {
        if (dock == null || robotId <= 0 || duration <= 0) return null;
        expire(now);
        Claim existing = byDock.get(dock);
        if (existing != null) {
            return existing.robotId() == robotId && existing.panelId() == panelId ? existing : null;
        }
        Claim prior = byRobot.get(robotId);
        if (prior != null) {
            // A robot cannot hold two destination reservations simultaneously.
            return null;
        }
        long token = nextToken++;
        if (token <= 0) { nextToken = 2L; token = 1L; }
        Claim created = new Claim(dock, panelId, robotId, token, now + duration);
        byDock.put(dock, created);
        byRobot.put(robotId, created);
        return created;
    }

    public boolean owned(Dock dock, long robotId, long token, long now) {
        Claim claim = byDock.get(dock);
        return claim != null && claim.robotId() == robotId && claim.token() == token
                && claim.expiresAt() > now;
    }

    public boolean reservedByOther(Dock dock, long robotId, long now) {
        Claim claim = byDock.get(dock);
        return claim != null && claim.robotId() != robotId && claim.expiresAt() > now;
    }

    public boolean renew(Dock dock, long panelId, long robotId, long token, long now, long duration) {
        Claim claim = byDock.get(dock);
        if (claim == null || claim.robotId() != robotId || claim.panelId() != panelId
                || claim.token() != token || claim.expiresAt() <= now || duration <= 0) return false;
        Claim renewed = new Claim(dock, panelId, robotId, token, now + duration);
        byDock.put(dock, renewed);
        byRobot.put(robotId, renewed);
        return true;
    }

    /** Keep a route while its robot is loaded and still executing the board. */
    public boolean renewRobot(long panelId, long robotId, long now, long duration) {
        Claim claim = byRobot.get(robotId);
        return claim != null && renew(claim.dock(), panelId, robotId, claim.token(), now, duration);
    }

    public boolean release(Dock dock, long robotId, long token) {
        Claim claim = byDock.get(dock);
        if (claim == null || claim.robotId() != robotId || claim.token() != token) return false;
        byDock.remove(dock);
        byRobot.remove(robotId, claim);
        return true;
    }

    public void clearRobot(long robotId) {
        Claim claim = byRobot.remove(robotId);
        if (claim != null) byDock.remove(claim.dock(), claim);
    }

    public void clearPanel(long panelId) {
        for (Claim claim : java.util.List.copyOf(byDock.values())) {
            if (claim.panelId() == panelId) release(claim.dock(), claim.robotId(), claim.token());
        }
    }

    public void expire(long now) {
        for (Claim claim : java.util.List.copyOf(byDock.values())) {
            if (claim.expiresAt() <= now) release(claim.dock(), claim.robotId(), claim.token());
        }
    }

    public int size() { return byDock.size(); }
}
