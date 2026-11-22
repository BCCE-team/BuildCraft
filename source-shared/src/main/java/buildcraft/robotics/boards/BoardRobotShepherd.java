package buildcraft.robotics.boards;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotSearchEntity;
import buildcraft.robotics.ai.AIRobotShearSheep;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
//? if >=1.21.11 {
import net.minecraft.world.entity.animal.sheep.Sheep;
//?} else {
/*?
import net.minecraft.world.entity.animal.Sheep;
?*/
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;

/** Shears adult sheep without harming them. Picker Robots handle dropped wool. */
public final class BoardRobotShepherd extends RedstoneBoardRobot {
    private static final float SEARCH_RANGE = 250.0F;
    private static final IStackFilter SHEARS = BoardRobotShepherd::isUsableShears;

    public BoardRobotShepherd(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("shepherd").nbt();
    }

    public static boolean isUsableShears(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ShearsItem
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    public static boolean isValidTarget(Entity entity) {
        return entity instanceof Sheep sheep && sheep.isAlive() && !sheep.isBaby() && !sheep.isSheared();
    }

    @Override
    public void update() {
        if (robot.getLinkedStation() == null) {
            startDelegateAI(new AIRobotGotoSleep(robot));
            return;
        }
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        // Never destroy an incompatible or worn-out tool just to equip shears.
        if (!held.isEmpty() && !isUsableShears(held)) {
            RobotBoardUtil.dropHeldItem(robot);
            return;
        }
        if (held.isEmpty()) {
            startDelegateAI(new AIRobotFetchAndEquipItemStack(robot, SHEARS));
        } else {
            startDelegateAI(new AIRobotSearchEntity(robot, BoardRobotShepherd::isValidTarget,
                    SEARCH_RANGE, robot.getZoneToWork()));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchEntity search) {
            if (search.success() && search.target instanceof Sheep sheep) {
                startDelegateAI(new AIRobotShearSheep(robot, sheep, search.claimToken()));
            } else {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotShearSheep) {
            if (!ai.success()) startDelegateAI(new AIRobotGotoSleep(robot));
        } else if (ai instanceof AIRobotFetchAndEquipItemStack) {
            if (!ai.success()) startDelegateAI(new AIRobotGotoSleep(robot));
        }
    }
}
