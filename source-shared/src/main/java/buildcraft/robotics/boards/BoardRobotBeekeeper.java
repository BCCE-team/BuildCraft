package buildcraft.robotics.boards;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotHarvestBeehive;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Harvests full bee nests/hives only while smoke prevents bee aggression. */
public final class BoardRobotBeekeeper extends BoardRobotGenericSearchBlock {
    private static final int BOTTLE_BATCH = 16;
    private static final IStackFilter BOTTLES = stack -> stack != null && stack.is(Items.GLASS_BOTTLE);
    private static final IStackFilter SHEARS = BoardRobotBeekeeper::isUsableShears;
    private boolean fetchingShears;

    public BoardRobotBeekeeper(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("beekeeper").nbt();
    }

    public static boolean isUsableShears(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof ShearsItem
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    public static boolean isUsableTool(ItemStack stack) {
        return stack != null && (stack.is(Items.GLASS_BOTTLE) || isUsableShears(stack));
    }

    /** A real, mature beehive with safe smoke. Do not force bees out or load adjacent chunks. */
    public static boolean isHarvestable(Level level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BeehiveBlock
                && state.hasProperty(BeehiveBlock.HONEY_LEVEL)
                && state.getValue(BeehiveBlock.HONEY_LEVEL) >= 5
                && CampfireBlock.isSmokeyPos(level, pos);
    }

    @Override
    public boolean isExpectedBlock(Level level, BlockPos pos) {
        return isUsableTool(robot.getItemBySlot(EquipmentSlot.MAINHAND)) && isHarvestable(level, pos);
    }

    @Override
    public void update() {
        if (robot.getLinkedStation() == null) {
            startDelegateAI(new AIRobotGotoSleep(robot));
            return;
        }
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!held.isEmpty() && !isUsableTool(held)) {
            // Never silently delete honey bottles, damaged shears, or a former board's tool.
            RobotBoardUtil.dropHeldItem(robot);
            return;
        }
        if (held.isEmpty()) {
            fetchingShears = false;
            startDelegateAI(new AIRobotFetchAndEquipItemStack(robot, BOTTLES, BOTTLE_BATCH));
        } else if (blockFound() != null) {
            startDelegateAI(new AIRobotHarvestBeehive(robot, blockFound()));
        } else {
            super.update(); // Gate-filtered, zone-constrained, reserved block search and flight.
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotFetchAndEquipItemStack) {
            if (!ai.success()) {
                if (!fetchingShears) {
                    // No bottles in an accessible supply station: try shears instead.
                    fetchingShears = true;
                    startDelegateAI(new AIRobotFetchAndEquipItemStack(robot, SHEARS));
                } else {
                    startDelegateAI(new AIRobotGotoSleep(robot));
                }
            }
        } else if (ai instanceof AIRobotHarvestBeehive) {
            if (blockFound() != null) {
                RobotProcessorWorkScheduler.executionFinished(robot, blockFound().toBlockPos(), ai.success());
            }
            releaseBlockFound(ai.success());
        } else {
            super.delegateAIEnded(ai);
        }
    }
}
