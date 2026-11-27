package buildcraft.robotics.internal.legacy.robots;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A packed robot is not a live robot. Its one-use identity survives world saves. */
public final class RobotIdentityLedger {
    private final Map<Long, String> packed = new LinkedHashMap<>();
    // HDD data remains on the disk; this index is only the authoritative controller assignment.
    // Empty string is an explicit unlink tombstone, distinct from an unknown legacy assignment.
    private final Map<Long, String> controllers = new LinkedHashMap<>();

    public String reserve(long id, String owner, String name) {
        if (id <= 0 || id == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid robot id");
        String token = UUID.randomUUID().toString();
        packed.put(id, proof(token, owner, name));
        return token;
    }

    private static String proof(String token, String owner, String name) {
        return token + ":" + owner + ":" + name;
    }

    public boolean matches(long id, String token, String owner, String name) {
        return id > 0 && !token.isEmpty() && proof(token, owner, name).equals(packed.get(id));
    }

    public boolean consume(long id, String token, String owner, String name) {
        return packed.remove(id, proof(token, owner, name));
    }

    public void restoreClaim(long id, String token, String owner, String name) {
        if (id > 0 && id < Long.MAX_VALUE && !token.isEmpty()) packed.putIfAbsent(id, proof(token, owner, name));
    }

    public boolean isPacked(long id) { return packed.containsKey(id); }
    public Map<Long, String> snapshot() { return Map.copyOf(packed); }
    public void clear() { packed.clear(); controllers.clear(); }

    /** Adopt a legacy HDD link only if no explicit link/unlink has ever been recorded. */
    public boolean adopt(long id, String disk) {
        if (id <= 0 || disk.isEmpty()) return false;
        controllers.putIfAbsent(id, disk);
        return controls(id, disk);
    }
    public void assign(long id, String disk) {
        if (id <= 0 || disk.isEmpty()) throw new IllegalArgumentException("Invalid controller binding");
        controllers.put(id, disk);
    }
    public boolean controls(long id, String disk) {
        return !disk.isEmpty() && disk.equals(controllers.get(id));
    }
    public boolean release(long id, String disk) {
        return !disk.isEmpty() && controllers.replace(id, disk, "");
    }
    public Map<Long, String> controllerSnapshot() { return Map.copyOf(controllers); }
    public void restoreController(long id, String disk) {
        if (id > 0 && id < Long.MAX_VALUE) controllers.put(id, disk);
    }
    public void restore(long id, String proof) {
        if (id > 0 && id < Long.MAX_VALUE && !proof.isEmpty()) packed.put(id, proof);
    }
}
