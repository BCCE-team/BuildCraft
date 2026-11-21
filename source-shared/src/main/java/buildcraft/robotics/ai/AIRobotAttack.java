package buildcraft.robotics.ai;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.entity.EntityRobot;
import buildcraft.robotics.scheduler.RobotCombatTargetScheduler;
import buildcraft.robotics.boards.BoardRobotButcher;
import buildcraft.robotics.boards.BoardRobotKnight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Moves into melee range and attacks with the equipped robot tool. */
public class AIRobotAttack extends AIRobot {
    private static final int ATTACK_DELAY_TICKS = 12;
    private Entity target;
    private long targetClaim;
    private int delay = 10;

    public AIRobotAttack(EntityRobotBase robot) {
        super(robot);
    }

    public AIRobotAttack(EntityRobotBase robot, Entity target) {
        this(robot);
        this.target = target;
    }

    public AIRobotAttack(EntityRobotBase robot, Entity target, long targetClaim) {
        this(robot, target);
        this.targetClaim = targetClaim;
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
        // The target may have escaped its originally valid work zone while
        // the robot was travelling. Never pursue or strike outside it.
        if (target == null || !target.isAlive() || target.isRemoved()
                || robot.getZoneToWork() != null && !robot.getZoneToWork().contains(target.position())
                // A previously valid animal can be tamed or named during the
                // chase. Recheck the board's rules before any further attacks.
                || robot.getBoard() instanceof BoardRobotButcher && !BoardRobotButcher.isValidTarget(target)
                || robot.getBoard() instanceof BoardRobotKnight && !BoardRobotKnight.isHostileTarget(target)) {
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
    public int getExecutionProgress() {
        if (!(target instanceof LivingEntity living) || !living.isAlive()) return -1;
        float max = living.getMaxHealth();
        if (max <= 0.0F) return -1;
        return Math.max(0, Math.min(100, Math.round((1.0F - living.getHealth() / max) * 100.0F)));
    }

    @Override
    public void end() {
        robot.setItemActive(false);
        releaseCombatClaim();
    }

    @Override
    public void onUnload() {
        super.onUnload();
        releaseCombatClaim();
    }

    private void releaseCombatClaim() {
        if (target != null && targetClaim != 0L) {
            RobotCombatTargetScheduler.finished(robot, target.getUUID(), targetClaim);
            targetClaim = 0L;
        }
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
