package buildcraft.robotics.ai;

import buildcraft.api.v2.OperationMode;
import buildcraft.api.v2.permission.WorldOperationKind;
import buildcraft.lib.misc.FakePlayerProvider;
import buildcraft.robotics.boards.BoardRobotShepherd;
import buildcraft.robotics.internal.api2.RobotAutomationSupport;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.scheduler.RobotCombatTargetScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
//? if >=1.21.11 {
import net.minecraft.world.entity.animal.sheep.Sheep;
//?} else {
/*?
import net.minecraft.world.entity.animal.Sheep;
?*/
//?}
import net.minecraft.world.entity.player.Player;

/** Navigates to a sheep and shears it as the robot owner; wool stays in the world for Picker Robots. */
public final class AIRobotShearSheep extends AIRobot {
    private static final int SHEAR_DELAY_TICKS = 12;
    /** 1.5 blocks between entity positions: the robot must get close enough to visibly shear. */
    private static final double SHEAR_REACH_SQ = 1.5D * 1.5D;
    private Sheep target;
    private long targetClaim;
    private int delay;

    /** RobotManager requires a one-argument constructor. Transient targets are rediscovered after reload. */
    public AIRobotShearSheep(EntityRobotBase robot) {
        this(robot, null, 0L);
    }

    public AIRobotShearSheep(EntityRobotBase robot, Sheep target, long claim) {
        super(robot);
        this.target = target;
        this.targetClaim = claim;
    }

    @Override
    public void preempt(AIRobot ai) {
        if (ai instanceof AIRobotGotoBlock && validTarget() && robot.distanceToSqr(target) <= SHEAR_REACH_SQ) {
            abortDelegateAI();
            robot.setItemActive(true);
        }
    }

    private boolean validTarget() {
        return target != null && BoardRobotShepherd.isValidTarget(target)
                && (robot.getZoneToWork() == null || robot.getZoneToWork().contains(target.position()));
    }

    @Override
    public void update() {
        if (!validTarget() || !(robot.getCommandSenderWorld() instanceof ServerLevel level)
                || !level.hasChunkAt(target.blockPosition())
                || !BoardRobotShepherd.isUsableShears(robot.getItemBySlot(EquipmentSlot.MAINHAND))) {
            setSuccess(false);
            terminate();
            return;
        }
        if (robot.distanceToSqr(target) > SHEAR_REACH_SQ) {
            BlockPos pos = target.blockPosition();
            startDelegateAI(new AIRobotGotoBlock(robot, pos.getX(), pos.getY(), pos.getZ()));
            robot.setItemActive(false);
            return;
        }
        robot.aimItemAt(target.blockPosition());
        robot.setItemActive(true);
        if (++delay < SHEAR_DELAY_TICKS) return;

        if (!validTarget() || !RobotAutomationSupport.permitsEntity(robot, level, target,
                WorldOperationKind.ENTITY_INTERACT, OperationMode.EXECUTE)) {
            setSuccess(false);
            terminate();
            return;
        }

        Player player = FakePlayerProvider.INSTANCE.getFakePlayer(level,
                RobotAutomationSupport.owner(robot), robot.blockPosition());
        player.setPos(robot.getX(), robot.getY(), robot.getZ());
        player.setItemInHand(InteractionHand.MAIN_HAND, robot.getItemBySlot(EquipmentSlot.MAINHAND).copy());
        // Use the same interaction as a player, so the sheep's actual vanilla
        // drop table, sounds, protection events and shears durability all apply.
        //? if mc_26_x {
        player.interactOn(target, InteractionHand.MAIN_HAND, target.position());
        //?} else {
        /*?
        player.interactOn(target, InteractionHand.MAIN_HAND);
        ?*/
        //?}
        robot.setItemInUse(player.getItemInHand(InteractionHand.MAIN_HAND));
        if (!target.isSheared()) {
            setSuccess(false);
            terminate();
            return;
        }

        // Leave vanilla wool drops untouched so a Picker Robot can collect them.
        terminate();
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotGotoBlock && !ai.success()) {
            robot.unreachableEntityDetected(target);
            setSuccess(false);
            terminate();
        }
    }

    @Override
    public void end() {
        robot.setItemActive(false);
        releaseClaim();
    }

    @Override
    public void onUnload() {
        super.onUnload();
        releaseClaim();
    }

    private void releaseClaim() {
        if (targetClaim != 0L && target != null) {
            RobotCombatTargetScheduler.finished(robot, target.getUUID(), targetClaim);
            targetClaim = 0L;
        }
    }

    @Override
    public int getEnergyCost() {
        return 12;
    }
}
