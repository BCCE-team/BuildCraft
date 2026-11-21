package buildcraft.robotics.scheduler;

import java.util.List;

/**
 * Value-only planning for a Builder robot's NEXT native blueprint reservation.
 *
 * The BlueprintBuilder establishes build/dependency order, zone and permission
 * checks, and authoritative ResourceIdBlock claims. This planner may only keep
 * a PREFIX of that sequence: sorting or skipping cells could build the top of
 * a door before the bottom, or bypass an excavation prerequisite.
 *
 * Movement costs are speculative, never an instruction to spend energy. The
 * normal robot charging AI remains authoritative. We always keep the first
 * reserved task so low batteries cannot erase the job or starve other builders.
 */
public final class RobotBuilderBatchPlanner {
    public static final int MAX_PANEL_OPERATIONS = 8;
    public static final int MAX_CARRIED_ITEMS = 128;
    private static final int APPROX_MOVEMENT_ENERGY_PER_BLOCK = 30;
    private static final int RETURN_MARGIN = 2_000;

    public record Point(int x, int y, int z) { }
    public record Operation(Point position, int energyCost, int items, boolean excavation) { }
    public record Plan(int operations, long estimatedEnergy, int items, int excavationTasks) { }

    private RobotBuilderBatchPlanner() { }

    public static Plan choose(List<Operation> ordered, Point robotPosition,
                              int queueSlots, long storedEnergy, long safetyEnergy) {
        if (ordered == null || robotPosition == null) throw new IllegalArgumentException("position/tasks");
        int maxJobs = Math.min(MAX_PANEL_OPERATIONS, Math.max(1, queueSlots));
        long usable = Math.max(0L, storedEnergy - Math.max(0L, safetyEnergy) - RETURN_MARGIN);
        long projected = 0L;
        int items = 0;
        int excavation = 0;
        int chosen = 0;
        Point previous = robotPosition;
        for (Operation operation : ordered) {
            if (chosen >= maxJobs) break;
            if (operation == null || operation.position() == null) {
                throw new IllegalArgumentException("null blueprint operation");
            }
            long movement = safeMultiply(manhattan(previous, operation.position()),
                    APPROX_MOVEMENT_ENERGY_PER_BLOCK);
            long cost = safeAdd(Math.max(0L, operation.energyCost()), movement);
            int additionalItems = Math.max(0, operation.items());
            if (chosen > 0 && (safeAdd(projected, cost) > usable
                    || (long) items + additionalItems > MAX_CARRIED_ITEMS)) break;
            // The native reservation already verifies that an individual task
            // fits in the robot. Do not produce an empty plan on low charge.
            projected = safeAdd(projected, cost);
            items = Math.min(Integer.MAX_VALUE, (int) Math.min(Integer.MAX_VALUE,
                    (long) items + additionalItems));
            if (operation.excavation()) excavation++;
            chosen++;
            previous = operation.position();
        }
        return new Plan(chosen, projected, items, excavation);
    }

    private static long manhattan(Point first, Point second) {
        return Math.abs((long) first.x() - second.x())
                + Math.abs((long) first.y() - second.y())
                + Math.abs((long) first.z() - second.z());
    }

    private static long safeMultiply(long a, long b) {
        return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
    }

    private static long safeAdd(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
