package buildcraft.robotics.ai;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.entity.EntityRobot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** Moves into melee range and attacks with the equipped robot tool. */
public class AIRobotAttack extends AIRobot {
    private static final int ATTACK_DELAY_TICKS = 12;
    private Entity target;
    private int delay = 10;

    public AIRobotAttack(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotAttack(EntityRobotBase robot, Entity target) {
        this(robot);
        this.target = target;
    }

    @Override
    public void preempt(AIRobot ai) {
        if (ai instanceof AIRobotGotoBlock && target != null && target.isAlive() && robot.distanceToSqr(target) <= 4.0D) {
            abortDelegateAI();
            robot.setItemActive(true);
        }
    }

    @Override
    public void update() {
        if (target == null || !target.isAlive() || target.isRemoved()) {
            terminate();
            return;
        }

        if (robot.distanceToSqr(target) > 4.0D) {
            BlockPos targetPos = target.blockPosition();
            startDelegateAI(new AIRobotGotoBlock(robot, targetPos.getX(), targetPos.getY(), targetPos.getZ()));
            robot.setItemActive(false);
            return;
        }

        robot.setItemActive(true);
        delay++;

        if (delay > ATTACK_DELAY_TICKS) {
            delay = 0;
            if (!RobotAutomationSupport.permitsEntity(
                robot, robot.getCommandSenderWorld(), target, WorldOperationKind.ENTITY_ATTACK, OperationMode.EXECUTE
            )) {
                setSuccess(false);
                terminate();
                return;
            }
            if (robot instanceof EntityRobot entityRobot) {
                entityRobot.attackTargetEntityWithCurrentItem(target);
            }
            robot.aimItemAt(target.blockPosition());
        }
    }

    @Override
    public void end() {
        robot.setItemActive(false);
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoBlock) {
            if (!ai.success() && target != null) {
                robot.unreachableEntityDetected(target);
            }
            terminate();
        }
    }

    @Override
    public int getEnergyCost() {
        return 16;
    }
}
