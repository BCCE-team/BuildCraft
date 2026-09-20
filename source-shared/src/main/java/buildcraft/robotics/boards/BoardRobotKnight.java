package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotAttack;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnload;
import buildcraft.robotics.ai.AIRobotSearchEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

/** Knight board that fetches a sword, then hunts hostile mobs and angry wolves in the work zone. */
public class BoardRobotKnight extends RedstoneBoardRobot {
    private static final float SEARCH_RANGE = 250.0F;

    public BoardRobotKnight(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("knight").nbt();
    }

    @Override
    public final void update() {
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!held.isEmpty() && !(held.getItem() instanceof SwordItem)) {
            RobotBoardUtil.dropHeldItem(robot);
            return;
        }
        if (held.isEmpty()) {
            startDelegateAI(new AIRobotFetchAndEquipItemStack(robot, new SwordFilter()));
        } else if (held.isDamageableItem() && held.getDamageValue() >= held.getMaxDamage()) {
            startDelegateAI(new AIRobotGotoStationAndUnload(robot));
        } else {
            startDelegateAI(new AIRobotSearchEntity(robot, BoardRobotKnight::isHostileTarget, SEARCH_RANGE, robot.getZoneToWork()));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotFetchAndEquipItemStack || ai instanceof AIRobotGotoStationAndUnload) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotSearchEntity search) {
            if (search.success()) {
                startDelegateAI(new AIRobotAttack(robot, search.target));
            } else {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        }
    }

    private static boolean isHostileTarget(Entity entity) {
        return entity instanceof Enemy
                || entity.getType().getCategory() == MobCategory.MONSTER
                || entity instanceof Wolf wolf && wolf.isAngry();
    }

    private static final class SwordFilter implements IStackFilter {
        @Override
        public boolean matches(ItemStack stack) {
            return !stack.isEmpty()
                    && stack.getItem() instanceof SwordItem
                    && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
        }
    }
}
