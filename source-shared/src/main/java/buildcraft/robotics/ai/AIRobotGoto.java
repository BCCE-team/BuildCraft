package buildcraft.robotics.ai;

import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.phys.Vec3;
import buildcraft.lib.balance.BalanceConfig;

public abstract class AIRobotGoto extends AIRobot {
    private static final double ROBOT_MOVE_SPEED = 0.15D;
    protected double nextX, nextY, nextZ;
    protected double dirX, dirY, dirZ;

    public AIRobotGoto(EntityRobotBase robot) {
        super(robot);
    }

    protected void setDestination(EntityRobotBase robot, double x, double y, double z) {
        nextX = x;
        nextY = y;
        nextZ = z;
        dirX = nextX - robot.getX();
        dirY = nextY - robot.getY();
        dirZ = nextZ - robot.getZ();
        double mag = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (mag != 0) {
            dirX /= mag;
            dirY /= mag;
            dirZ /= mag;
        } else {
            dirX = dirY = dirZ = 0;
        }
        String key = robot.getBoard() == null ? "empty" : robot.getBoard().getNBTHandler().getID();
        double speed = ROBOT_MOVE_SPEED * BalanceConfig.robot(key, "movement_speed_multiplier", 1.0);
        robot.setDeltaMovement(new Vec3(dirX * speed, dirY * speed, dirZ * speed));
    }

    @Override
    public int getEnergyCost() {
        return 3;
    }
}
