package buildcraft.robotics.boards;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotFish;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;

/** A rod-and-bobber fisher. All loot remains in the world for Picker Robots. */
public final class BoardRobotFisher extends RedstoneBoardRobot {
    private static final IStackFilter RODS = BoardRobotFisher::isUsableRod;

    public BoardRobotFisher(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("fisher").nbt();
    }

    public static boolean isUsableRod(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof FishingRodItem
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    @Override
    public void update() {
        if (robot.getLinkedStation() == null) {
            startDelegateAI(new AIRobotGotoSleep(robot));
            return;
        }
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!held.isEmpty() && !isUsableRod(held)) {
            RobotBoardUtil.dropHeldItem(robot);
            return;
        }
        if (held.isEmpty()) {
            startDelegateAI(new AIRobotFetchAndEquipItemStack(robot, RODS));
        } else {
            startDelegateAI(new AIRobotFish(robot));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if ((ai instanceof AIRobotFetchAndEquipItemStack || ai instanceof AIRobotFish) && !ai.success()) {
            startDelegateAI(new AIRobotGotoSleep(robot));
        }
    }
}
