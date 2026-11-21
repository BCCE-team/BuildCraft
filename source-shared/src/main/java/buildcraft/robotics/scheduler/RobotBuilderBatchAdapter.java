package buildcraft.robotics.scheduler;

import java.util.ArrayList;
import java.util.List;

import buildcraft.builders.snapshot.BlueprintBuilder.RobotBuildTask;
import buildcraft.builders.tile.IRobotBuilderTarget;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Bridges the pure prefix planner to native BlueprintBuilder reservations. */
public final class RobotBuilderBatchAdapter {
    private RobotBuilderBatchAdapter() { }

    /**
     * After the Builder authoritatively reserves an ORDERED batch, promptly
     * release every unused suffix task using the SAME actor. Never sort,
     * manufacture reservations, or move tasks between robots/sources.
     *
     * The autonomous path does not call this method.
     */
    public static List<RobotBuildTask> retainNativePrefix(EntityRobotBase robot,
            IRobotBuilderTarget source, List<RobotBuildTask> reserved, int queueSlots) {
        if (reserved == null || reserved.isEmpty()) return List.of();
        BlockPos origin = robot.blockPosition();
        List<RobotBuilderBatchPlanner.Operation> costs = new ArrayList<>(reserved.size());
        for (RobotBuildTask task : reserved) {
            BlockPos at = task.pos();
            if (at == null) throw new IllegalArgumentException("Blueprint task without position");
            int items = 0;
            for (ItemStack requirement : task.requirements()) {
                if (requirement != null && !requirement.isEmpty()) {
                    items = (int) Math.min(Integer.MAX_VALUE, (long) items + Math.max(0, requirement.getCount()));
                }
            }
            costs.add(new RobotBuilderBatchPlanner.Operation(
                    new RobotBuilderBatchPlanner.Point(at.getX(), at.getY(), at.getZ()),
                    task.energyCost(), items, task.breakTask()));
        }
        RobotBuilderBatchPlanner.Plan plan = RobotBuilderBatchPlanner.choose(costs,
                new RobotBuilderBatchPlanner.Point(origin.getX(), origin.getY(), origin.getZ()),
                queueSlots, robot.getEnergy(), EntityRobotBase.SAFETY_ENERGY);
        int keep = plan.operations();
        for (int i = reserved.size() - 1; i >= keep; --i) {
            source.releaseRobotBuildTask(robot, reserved.get(i));
        }
        return new ArrayList<>(reserved.subList(0, keep));
    }
}
