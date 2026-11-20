package buildcraft.robotics.boards;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.ai.AIRobotBreak;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnload;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public abstract class BoardRobotGenericBreakBlock extends BoardRobotGenericSearchBlock {
    public BoardRobotGenericBreakBlock(EntityRobotBase robot) {
        super(robot);
    }

    public abstract boolean isExpectedTool(ItemStack stack);

    @Override
    public void update() {
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        boolean toolRequired = !isExpectedTool(ItemStack.EMPTY);
        if (toolRequired && !held.isEmpty() && !isExpectedTool(held)) {
            RobotBoardUtil.dropHeldItem(robot);
            return;
        }
        if (toolRequired && held.isEmpty()) {
            startDelegateAI(new AIRobotFetchAndEquipItemStack(robot, new ExpectedToolFilter()));
        } else if (!held.isEmpty() && held.isDamageableItem() && held.getDamageValue() >= held.getMaxDamage()) {
            startDelegateAI(new AIRobotGotoStationAndUnload(robot));
        } else if (blockFound() != null) {
            startDelegateAI(new AIRobotBreak(robot, blockFound()));
        } else {
            super.update();
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotFetchAndEquipItemStack || ai instanceof AIRobotGotoStationAndUnload) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotBreak) {
            if (blockFound() != null) {
                // The queue counts the break operation, not the path or search.
                // Report only the actual executor's result, never a prediction.
                RobotProcessorWorkScheduler.executionFinished(robot, blockFound().toBlockPos(), ai.success());
            }
            releaseBlockFound(ai.success());
        }
        super.delegateAIEnded(ai);
    }

    private class ExpectedToolFilter implements IStackFilter {
        @Override
        public boolean matches(ItemStack stack) {
            return !stack.isEmpty()
                    && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage())
                    && isExpectedTool(stack);
        }
    }
}
