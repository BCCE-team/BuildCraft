package buildcraft.robotics.block;

import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** The control panel uses the same facing and interaction conventions as Zone Planner. */
public class BlockRobotProcessor extends BlockZonePlanner {
    @Override
    public TileBC_Neptune newBlockEntity(BlockPos pos, BlockState state) {
        return new TileRobotProcessor(pos, state);
    }
}
