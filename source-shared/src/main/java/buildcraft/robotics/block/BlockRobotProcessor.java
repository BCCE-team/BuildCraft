package buildcraft.robotics.block;

import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.robotics.tile.TileRobotProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** The control panel uses the same facing and interaction conventions as Zone Planner. */
public class BlockRobotProcessor extends BlockZonePlanner {
    public static final BooleanProperty ACTIVE = BuildCraftProperties.ACTIVE;

    public BlockRobotProcessor() {
        super();
        registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ACTIVE);
    }

    @Override
    public TileBC_Neptune newBlockEntity(BlockPos pos, BlockState state) {
        return new TileRobotProcessor(pos, state);
    }
}
