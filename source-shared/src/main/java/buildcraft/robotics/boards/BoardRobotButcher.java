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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Ocelot;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

/** Butcher board that fetches a sword, then hunts passive animals in the work zone. */
public class BoardRobotButcher extends RedstoneBoardRobot {
    private static final float SEARCH_RANGE = 250.0F;

    public BoardRobotButcher(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("butcher").nbt();
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
            startDelegateAI(new AIRobotSearchEntity(robot, BoardRobotButcher::isValidTarget, SEARCH_RANGE, robot.getZoneToWork()));
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

    private static boolean isValidTarget(net.minecraft.world.entity.Entity entity) {
        if (!(entity instanceof Animal animal) || animal.hasCustomName()) {
            return false;
        }
        // Cats and dogs are never valid butcher targets, even when they are wild.
        if (animal instanceof Cat || animal instanceof Ocelot || animal instanceof Wolf) {
            return false;
        }
        // Ignore all common tameable/owned passive animals.
        if (animal instanceof TamableAnimal tameable && tameable.isTame()) {
            return false;
        }
        return !(animal instanceof AbstractHorse horse) || !horse.isTamed();
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
