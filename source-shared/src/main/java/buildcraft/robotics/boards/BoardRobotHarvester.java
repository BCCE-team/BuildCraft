package buildcraft.robotics.boards;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.robotics.internal.legacy.boards.RedstoneBoardRobotNBT;
import buildcraft.robotics.internal.legacy.robots.AIRobot;
import buildcraft.robotics.internal.legacy.robots.EntityRobotBase;
import buildcraft.robotics.BCRoboticsBoards;
import buildcraft.robotics.scheduler.RobotProcessorWorkScheduler;
import buildcraft.robotics.ai.AIRobotHarvest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Harvester board that searches for mature crops and harvests them. */
public class BoardRobotHarvester extends BoardRobotGenericSearchBlock {
    public BoardRobotHarvester(EntityRobotBase robot) {
        super(robot);
    }

    @Override
    public boolean processorSharedScanPredicate() { return true; }

    @Override
    public RedstoneBoardRobotNBT getNBTHandler() {
        return BCRoboticsBoards.getByKey("harvester").nbt();
    }

    @Override
    public boolean isExpectedBlock(Level level, BlockPos pos) {
        return level.isLoaded(pos) && BuildCraftApi.service(BuildCraftServices.CROPS).isMature(level, level.getBlockState(pos), pos);
    }

    private Level robotLevel() {
        //? if <1.20 {
        return robot.level;
        //?} else {
        /*?
        return robot.level();
        ?*/
        //?}
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
            if (blockFound() != null) {
                RobotProcessorWorkScheduler.executionFinished(robot, blockFound().toBlockPos(), ai.success());
            }
            releaseBlockFound(ai.success());
        }
        super.delegateAIEnded(ai);
    }
}
