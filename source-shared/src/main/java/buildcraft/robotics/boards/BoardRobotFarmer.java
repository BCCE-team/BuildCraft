package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobot;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.lib.internal.core.BlockIndex;
import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.internal.legacy.robots.ResourceIdBlock;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotFetchAndEquipItemStack;
import buildcraft.robotics.ai.AIRobotGotoSleep;
import buildcraft.robotics.ai.AIRobotGotoStationAndUnload;
import buildcraft.robotics.ai.AIRobotSearchAndGotoBlock;
import buildcraft.robotics.ai.AIRobotUseToolOnBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Farmer board that fetches a hoe, finds tillable dirt-like blocks, and hoes them. */
public class BoardRobotFarmer extends RedstoneBoardRobot {
    private BlockIndex blockFound;

    public BoardRobotFarmer(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("farmer").nbt();
    }

    @Override
    public void update() {
        ItemStack held = robot.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!held.isEmpty() && !(held.getItem() instanceof HoeItem)) {
            RobotBoardUtil.dropHeldItem(robot);
            return;
        }
        if (held.isEmpty()) {
            startDelegateAI(new AIRobotFetchAndEquipItemStack(robot, new HoeFilter()));
        } else if (held.isDamageableItem() && held.getDamageValue() >= held.getMaxDamage()) {
            startDelegateAI(new AIRobotGotoStationAndUnload(robot));
        } else {
            startDelegateAI(new AIRobotSearchAndGotoBlock(robot, false, this::isExpectedBlock));
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotSearchAndGotoBlock search) {
            if (search.success()) {
                blockFound = search.getBlockFound();
                startDelegateAI(new AIRobotUseToolOnBlock(robot, blockFound));
            } else {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotFetchAndEquipItemStack || ai instanceof AIRobotGotoStationAndUnload) {
            if (!ai.success()) {
                startDelegateAI(new AIRobotGotoSleep(robot));
            }
        } else if (ai instanceof AIRobotUseToolOnBlock) {
            releaseBlockFound();
        }
    }

    @Override
    public void end() {
        releaseBlockFound();
    }

    private boolean isExpectedBlock(Level level, BlockPos pos) {
        if (!level.isLoaded(pos) || !isAirAbove(level, pos)) {
            return false;
        }
        if (robot.getRegistry() != null && robot.getRegistry().isTaken(new ResourceIdBlock(pos))) {
            return false;
        }
        return isTillable(level.getBlockState(pos));
    }

    private static boolean isTillable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL);
    }

    private static boolean isAirAbove(Level level, BlockPos pos) {
        return level.isEmptyBlock(pos.above());
    }

    private void releaseBlockFound() {
        if (blockFound != null) {
            if (robot.getRegistry() != null) {
                robot.getRegistry().release(new ResourceIdBlock(blockFound));
            }
            blockFound = null;
        }
    }

    @Override
    public void writeSelfToNBT(CompoundTag nbt) {
        if (blockFound != null) {
            CompoundTag tag = new CompoundTag();
            blockFound.writeTo(tag);
            nbt.put("blockFound", tag);
        }
    }

    @Override
    public void loadSelfFromNBT(CompoundTag nbt) {
        if (nbt.contains("blockFound")) {
            blockFound = new BlockIndex(nbt.getCompound("blockFound"));
        }
    }

    private static boolean isHoe(ItemStack stack) {
        return !stack.isEmpty()
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage())
                && stack.getItem() instanceof HoeItem;
    }

    private static final class HoeFilter implements IStackFilter {
        @Override
        public boolean matches(ItemStack stack) {
            return isHoe(stack);
        }
    }
}
