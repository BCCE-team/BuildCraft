package buildcraft.robotics.scheduler;

import java.util.List;

/**
 * Value-only energy preflight for one useful robot trip. No inventories, AIs or
 * chunks are accessed here. This estimates movement and a SMALL batch of work;
 * a connected vein is not required to fit into one battery charge.
 *
 * Estimates are deliberately conservative but are NOT pathfinding guarantees:
 * a winding corridor may be longer than Manhattan distance. AIRobotMain and
 * AIRobotRecharge remain the final authority for safe return and charging.
 */
public final class RobotWorkEnergyBudget {
    public static final int MAX_BATTERY_ENERGY = 100_000;
    public static final double MOVEMENT_SPEED = 0.15D;
    public static final int MOVEMENT_COST_PER_TICK = 3;
    public static final int WORK_COST_PER_TICK = 8;
    public static final int WORK_TICKS_PER_BLOCK = 40;
    public static final int RETURN_FIXED_RESERVE = 2_000;
    public static final int MAX_PLANNED_BLOCKS = 4;
    /** Extra route margin for obstacles and imperfect Manhattan estimates. */
    private static final double ROUTE_FACTOR = 1.5D;
    private static final int BUSY_DOCK_MARGIN = 800;

    public record Point(double x, double y, double z) {
        public double distance(Point other) {
            return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
        }
    }
    public record Charger(Point position, boolean available) { }
    public record Estimate(boolean chargerKnown, boolean affordable, long required,
                           int estimatedBlocks, long travel, long work) { }

    private RobotWorkEnergyBudget() { }

    /**
     * Always budget at least ONE block. Only calculate up to four consecutive
     * blocks of a larger vein, since the miner will revalidate after every
     * break and can recharge mid-vein. Never reserve energy for all future
     * items in a queue: they are speculative, not committed travel.
     */
    public static Estimate estimate(Point robot, Point target, List<Charger> chargers,
                                    int stored, int safetyReserve, double speedMultiplier,
                                    double costMultiplier, int plannedBlocks) {
        if (robot == null || target == null) throw new IllegalArgumentException("position");
        int steps = Math.max(1, Math.min(MAX_PLANNED_BLOCKS, plannedBlocks));
        if (chargers == null || chargers.isEmpty()) {
            // A modpack may charge robots externally, without any registered
            // powered dock. Preserve existing gameplay; this is advisory ONLY.
            return new Estimate(false, stored >= safetyReserve, safetyReserve, steps, 0, 0);
        }
        double speed = MOVEMENT_SPEED * Math.max(0.05D, speedMultiplier);
        double multiplier = Math.max(0.1D, costMultiplier);
        long travelOut = movementCost(robot.distance(target), speed, multiplier);
        long oneWork = Math.max(1L, (long) Math.ceil(WORK_COST_PER_TICK
                * WORK_TICKS_PER_BLOCK * multiplier));
        long betweenWork = movementCost(2.0D, speed, multiplier);
        // Prefer chargers that are currently free. If all are occupied,
        // account for a little waiting and let the charging AI arbitrate.
        boolean freeCharger = chargers.stream().anyMatch(Charger::available);
        long returnCost = Long.MAX_VALUE;
        for (Charger charger : chargers) {
            if (freeCharger && !charger.available()) continue;
            long returnEstimate = movementCost(target.distance(charger.position()), speed, multiplier);
            returnCost = Math.min(returnCost, returnEstimate);
        }
        if (returnCost == Long.MAX_VALUE) return new Estimate(false, true, safetyReserve, steps, 0, 0);
        long margin = freeCharger ? 0 : BUSY_DOCK_MARGIN;
        long base = saturatingAdd(Math.max(0, safetyReserve), RETURN_FIXED_RESERVE);
        base = saturatingAdd(base, travelOut);
        base = saturatingAdd(base, returnCost);
        base = saturatingAdd(base, margin);
        long work = saturatingAdd(oneWork * steps, betweenWork * (steps - 1L));
        long requested = saturatingAdd(base, work);
        long oneBlockRequired = saturatingAdd(base, oneWork);
        int achievable = 0;
        if (stored >= oneBlockRequired) {
            for (int i = 1; i <= steps; i++) {
                long cost = saturatingAdd(base,
                        saturatingAdd(oneWork * i, betweenWork * (i - 1L)));
                if (stored < cost) break;
                achievable = i;
            }
        }
        return new Estimate(true, achievable >= 1, oneBlockRequired,
                achievable, saturatingAdd(travelOut, returnCost), work);
    }

    private static long movementCost(double blocks, double speed, double multiplier) {
        if (!Double.isFinite(blocks) || blocks < 0) return Long.MAX_VALUE;
        double ticks = Math.ceil(blocks / speed);
        double result = ticks * MOVEMENT_COST_PER_TICK * multiplier * ROUTE_FACTOR;
        return !Double.isFinite(result) || result >= Long.MAX_VALUE
                ? Long.MAX_VALUE : (long) Math.ceil(result);
    }

    private static long saturatingAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
