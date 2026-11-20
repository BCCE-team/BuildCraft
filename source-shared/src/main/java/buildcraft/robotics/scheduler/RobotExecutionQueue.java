package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded queue of useful operations. For miners one job is one connected ore
 * deposit, even when breaking it requires many individual block operations.
 * Travel, searching and breaking subsequent blocks of the same deposit do not
 * consume additional slots. This value-only core is independent of Minecraft.
 */
public final class RobotExecutionQueue {
    public static final class Job {
        private final LinkedHashMap<Long, Long> members = new LinkedHashMap<>();
        private boolean active;
        private long activeTarget;

        private Job(Map<Long, Long> targets) { members.putAll(targets); }

        /** The current break target, or a still-unmined representative. */
        public long position() {
            if (active && members.containsKey(activeTarget)) return activeTarget;
            return members.keySet().iterator().next();
        }

        public long leaseToken() { return members.get(position()); }
        /** If the currently executing block is saved just before breaking,
         * resume from another remaining vein member rather than from stale air. */
        public long recoveryPosition() {
            for (long pos : members.keySet()) if (!active || pos != activeTarget) return pos;
            return position();
        }
        public boolean active() { return active; }
        public boolean contains(long position) { return members.containsKey(position); }
        public List<Long> positions() { return List.copyOf(members.keySet()); }
        public Map<Long, Long> reservations() { return Map.copyOf(members); }
        public int remainingBlocks() { return members.size(); }
    }

    private final Map<Long, ArrayList<Job>> byRobot = new HashMap<>();

    public List<Job> jobs(long robotId) {
        List<Job> items = byRobot.get(robotId);
        return items == null ? List.of() : List.copyOf(items);
    }

    /** Number of deposits, NOT the number of ore blocks. */
    public int size(long robotId) {
        List<Job> items = byRobot.get(robotId);
        return items == null ? 0 : items.size();
    }

    public boolean contains(long robotId, long position) {
        for (Job job : jobs(robotId)) if (job.contains(position)) return true;
        return false;
    }

    public long token(long robotId, long position) {
        for (Job job : jobs(robotId)) {
            Long token = job.members.get(position);
            if (token != null) return token;
        }
        return 0L;
    }

    /** Compatibility with existing single-block operations. */
    public boolean add(long robotId, long position, long token, int capacity) {
        if (contains(robotId, position)) return false;
        return addDeposit(robotId, Map.of(position, token), capacity);
    }

    /**
     * Add a deposit, or merge newly discovered neighbours into an existing
     * deposit. If two provisional groups were actually connected, merge them
     * without consuming two slots or dropping an executing member.
     */
    public boolean addDeposit(long robotId, Map<Long, Long> targets, int capacity) {
        if (robotId <= 0L || capacity < 1 || capacity > 8 || targets.isEmpty()) return false;
        ArrayList<Job> items = byRobot.computeIfAbsent(robotId, ignored -> new ArrayList<>());
        List<Job> overlapping = new ArrayList<>();
        for (Job job : items) {
            for (long pos : targets.keySet()) {
                if (job.contains(pos)) { overlapping.add(job); break; }
            }
        }
        if (overlapping.isEmpty()) {
            if (items.size() >= capacity) return false;
            items.add(new Job(targets));
            return true;
        }
        Job primary = overlapping.stream().filter(job -> job.active)
                .findFirst().orElse(overlapping.get(0));
        for (Job existing : overlapping) {
            if (existing == primary) continue;
            existing.members.forEach(primary.members::putIfAbsent);
            items.remove(existing);
        }
        targets.forEach(primary.members::putIfAbsent);
        return true;
    }

    /** Move the real, successfully reserved block's entire deposit to the head. */
    public boolean activate(long robotId, long position) {
        ArrayList<Job> items = byRobot.get(robotId);
        if (items == null) return false;
        for (int i = 0; i < items.size(); i++) {
            Job job = items.get(i);
            if (job.contains(position)) {
                items.remove(i);
                items.add(0, job);
                job.active = true;
                job.activeTarget = position;
                return true;
            }
        }
        return false;
    }

    /**
     * Remove ONE completed/invalid block. The deposit occupies its single slot
     * until its last known member is completed or abandoned. The returned job
     * contains only the removed block so callers release exactly its lease.
     */
    public Job remove(long robotId, long position) {
        ArrayList<Job> items = byRobot.get(robotId);
        if (items == null) return null;
        for (int i = 0; i < items.size(); i++) {
            Job job = items.get(i);
            Long token = job.members.remove(position);
            if (token == null) continue;
            Job removed = new Job(Map.of(position, token));
            removed.active = job.active && job.activeTarget == position;
            removed.activeTarget = position;
            if (job.activeTarget == position) job.active = false;
            if (job.members.isEmpty()) items.remove(i);
            if (items.isEmpty()) byRobot.remove(robotId);
            return removed;
        }
        return null;
    }

    /** Cancel an entire deposit and return all its reservations for release. */
    public Job removeDeposit(long robotId, long position) {
        ArrayList<Job> items = byRobot.get(robotId);
        if (items == null) return null;
        for (int i = 0; i < items.size(); i++) {
            Job job = items.get(i);
            if (!job.contains(position)) continue;
            items.remove(i);
            if (items.isEmpty()) byRobot.remove(robotId);
            return job;
        }
        return null;
    }

    /** Drop only whole pending deposits, preserving the current active deposit. */
    public List<Job> trim(long robotId, int capacity) {
        ArrayList<Job> items = byRobot.get(robotId);
        if (items == null) return List.of();
        List<Job> removed = new ArrayList<>();
        int limit = Math.max(0, Math.min(8, capacity));
        while (items.size() > limit) removed.add(items.remove(items.size() - 1));
        if (items.isEmpty()) byRobot.remove(robotId);
        return removed;
    }

    public List<Job> clear(long robotId) {
        List<Job> removed = jobs(robotId);
        byRobot.remove(robotId);
        return removed;
    }

    public List<Job> clearAll() {
        List<Job> removed = new ArrayList<>();
        for (ArrayList<Job> items : byRobot.values()) removed.addAll(items);
        byRobot.clear();
        return removed;
    }

    public List<Long> robotIds() { return List.copyOf(byRobot.keySet()); }
}
