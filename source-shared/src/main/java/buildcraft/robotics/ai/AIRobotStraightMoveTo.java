package buildcraft.robotics.ai;

import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.RobotTrace;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

public class AIRobotStraightMoveTo extends AIRobotGoto {
    private double prevDistance = Double.MAX_VALUE;
    private int stalledTicks;
    private double x, y, z;

    public AIRobotStraightMoveTo(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotStraightMoveTo(EntityRobotBase robot, double x, double y, double z) {
        this(robot);
        this.x = x;
        this.y = y;
        this.z = z;
        robot.aimItemAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public void start() {
        robot.undock();
        setDestination(robot, x, y, z);
        if (RobotTrace.enabled()) RobotTrace.event(robot, "STRAIGHT_START", "target=(%.3f,%.3f,%.3f)", x, y, z);
    }

    @Override
    public void update() {
        double d = Math.sqrt(robot.distanceToSqr(x, y, z));
        if (d <= 0.05D) {
            robot.setDeltaMovement(Vec3.ZERO);
            robot.setPos(x, y, z); // Only snap the final few centimeters on arrival.
            if (RobotTrace.enabled()) RobotTrace.event(robot, "STRAIGHT_ARRIVED", "target=(%.3f,%.3f,%.3f)", x, y, z);
            terminate();
            return;
        }
        // Lack of progress is not arrival: an obstacle must never teleport the robot.
        stalledTicks = d >= prevDistance - 0.005D ? stalledTicks + 1 : 0;
        prevDistance = d;
        if (stalledTicks >= 20) {
            robot.setDeltaMovement(Vec3.ZERO);
            if (RobotTrace.enabled()) RobotTrace.event(robot, "STRAIGHT_STALLED",
                    "target=(%.3f,%.3f,%.3f) distance=%.4f stalledTicks=%d", x, y, z, d, stalledTicks);
            setSuccess(false);
            terminate();
            return;
        }
        // Never keep the initial unbounded velocity: recompute the direction
        // each tick and clamp the final step to the exact remaining distance.
        setDestination(robot, x, y, z);
        Vec3 step = robot.getDeltaMovement();
        double length = step.length();
        if (length > d && length > 0.0D) {
            robot.setDeltaMovement(step.scale(d / length));
        }
        if (RobotTrace.enabled()) RobotTrace.event(robot, "STRAIGHT_PROGRESS",
                "target=(%.3f,%.3f,%.3f) distance=%.4f stalledTicks=%d", x, y, z, d, stalledTicks);
    }

    @Override
    public boolean canLoadFromNBT() {
        return true;
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        nbt.putDouble("x", x);
        nbt.putDouble("y", y);
        nbt.putDouble("z", z);
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        x = nbt.getDouble("x");
        y = nbt.getDouble("y");
        z = nbt.getDouble("z");
        prevDistance = Double.MAX_VALUE;
        stalledTicks = 0;
    }
}
