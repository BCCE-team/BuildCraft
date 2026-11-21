package buildcraft.robotics.scheduler;

/**
 * One panel's server-thread-only navigation workload, published in 20-tick
 * windows. Counters are observational: no paths, chunks, world entities,
 * timers, NBT or worker references are retained. A GUI reads only a snapshot.
 *
 * <p>The reference A* nodes displayed for a direct cache hit are an ESTIMATE:
 * they are the nodes expanded by the original successful search, not a
 * counterfactual second search that has actually been run.</p>
 */
public final class RobotProcessorNavigationProfiler {
    public static final int WINDOW_TICKS = 20;
    public static final int MAX_WIRE_COUNTER = 100_000_000;

    public record Snapshot(int cacheProbes, int cacheCandidates, int directHits,
            int warmStarts, int rejectedRoutes, int storedRoutes,
            int validatedWaypoints, int aStarPolled, int aStarExpanded,
            int grantedNodes, int refundedNodes, int sharedWaitRobotTicks,
            int budgetWaitRobotTicks, int emptyQueueRobotTicks,
            int readyMinerRobotTicks, int estimatedReferenceNodesBypassed,
            int cachedRoutes) {
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private long firstTick = Long.MIN_VALUE;
    private int probes, candidates, direct, warm, rejected, stored;
    private int validated, polled, expanded, granted, refunded, sharedWait, budgetWait;
    private int emptyQueueRobotTicks, readyMinerRobotTicks;
    private int estimatedBypassed;
    private Snapshot completed = Snapshot.EMPTY;

    /** The first read of a new window publishes the previous complete sample. */
    public void beginTick(long tick) {
        if (firstTick == Long.MIN_VALUE) {
            firstTick = tick;
        } else if (tick < firstTick || tick - firstTick >= WINDOW_TICKS) {
            completed = snapshotOfCounters(0);
            clearCounters();
            firstTick = tick;
        }
    }

    /** A lookup is counted once even when there is no matching witness. */
    public void lookup(boolean candidateFound) {
        probes = plus(probes, 1);
        if (candidateFound) candidates = plus(candidates, 1);
    }

    public void stored() { stored = plus(stored, 1); }
    public void directHit(int referenceExpanded) {
        direct = plus(direct, 1);
        estimatedBypassed = plus(estimatedBypassed, Math.max(0, referenceExpanded));
    }
    public void warmStart() { warm = plus(warm, 1); }
    public void rejected() { rejected = plus(rejected, 1); }
    public void validated(int count) { validated = plus(validated, count); }
    public void search(int nodesPolled, int nodesExpanded) {
        polled = plus(polled, nodesPolled);
        expanded = plus(expanded, nodesExpanded);
    }
    /** Granted node count includes both native A* and cached-route validation. */
    public void grant(int nodes) { granted = plus(granted, nodes); }
    /** Record only credits ACTUALLY reimbursed by the shared world budget. */
    public void refund(int nodes) { refunded = plus(refunded, nodes); }
    public void sharedWait() { sharedWait = plus(sharedWait, 1); }
    public void budgetWait() { budgetWait = plus(budgetWait, 1); }

    /** Called once per loaded-panel tick after native work queue maintenance. */
    public void sampleQueues(int readyMiners, int readyWithEmptyQueue) {
        int ready = Math.max(0, readyMiners);
        readyMinerRobotTicks = plus(readyMinerRobotTicks, ready);
        emptyQueueRobotTicks = plus(emptyQueueRobotTicks,
                Math.min(ready, Math.max(0, readyWithEmptyQueue)));
    }

    /** The cache size is live metadata; the other numbers are from a finished window. */
    public Snapshot snapshot(int cachedRoutes) {
        Snapshot c = completed;
        return new Snapshot(c.cacheProbes(), c.cacheCandidates(), c.directHits(),
                c.warmStarts(), c.rejectedRoutes(), c.storedRoutes(),
                c.validatedWaypoints(), c.aStarPolled(), c.aStarExpanded(),
                c.grantedNodes(), c.refundedNodes(), c.sharedWaitRobotTicks(),
                c.budgetWaitRobotTicks(), c.emptyQueueRobotTicks(),
                c.readyMinerRobotTicks(), c.estimatedReferenceNodesBypassed(),
                Math.max(0, cachedRoutes));
    }

    public void reset() {
        firstTick = Long.MIN_VALUE;
        completed = Snapshot.EMPTY;
        clearCounters();
    }

    private Snapshot snapshotOfCounters(int cachedRoutes) {
        return new Snapshot(probes, candidates, direct, warm, rejected, stored,
                validated, polled, expanded, granted, refunded, sharedWait,
                budgetWait, emptyQueueRobotTicks, readyMinerRobotTicks,
                estimatedBypassed, cachedRoutes);
    }

    private void clearCounters() {
        probes = candidates = direct = warm = rejected = stored = 0;
        validated = polled = expanded = granted = refunded = sharedWait = budgetWait = 0;
        emptyQueueRobotTicks = readyMinerRobotTicks = estimatedBypassed = 0;
    }

    private static int plus(int current, int addition) {
        if (addition <= 0) return current;
        return addition > MAX_WIRE_COUNTER - current ? MAX_WIRE_COUNTER : current + addition;
    }
}
