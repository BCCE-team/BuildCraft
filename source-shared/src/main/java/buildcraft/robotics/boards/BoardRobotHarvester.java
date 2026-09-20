package buildcraft.robotics.boards;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.ai.AIRobotHarvest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Harvester board that searches for mature crops and harvests them. */
public class BoardRobotHarvester extends BoardRobotGenericSearchBlock {
    public BoardRobotHarvester(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("harvester").nbt();
    }

    @Override
    public boolean isExpectedBlock(Level level, BlockPos pos) {
        return level.isLoaded(pos) && BuildCraftApi.service(BuildCraftServices.CROPS).isMature(level, level.getBlockState(pos), pos);
    }

    @Override
    public void update() {
        if (blockFound() != null) {
            startDelegateAI(new AIRobotHarvest(robot, blockFound()));
        } else {
            super.update();
        }
    }

    @Override
    public void delegateAIEnded(AIRobot ai) {
        if (ai instanceof AIRobotHarvest) {
            releaseBlockFound(ai.success());
        }
        super.delegateAIEnded(ai);
    }
}
