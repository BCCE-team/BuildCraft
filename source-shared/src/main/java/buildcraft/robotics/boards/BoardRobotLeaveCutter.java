package buildcraft.robotics.boards;

import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;

/** Leaf-cutter board that fetches shears and cuts leaf blocks. */
public class BoardRobotLeaveCutter extends BoardRobotGenericBreakBlock {
    public BoardRobotLeaveCutter(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("leave_cutter").nbt();
    }

    @Override
    public boolean isExpectedTool(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof ShearsItem;
    }

    @Override
    public boolean isExpectedBlock(Level level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockState(pos).is(BlockTags.LEAVES);
    }
}
