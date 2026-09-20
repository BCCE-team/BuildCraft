package buildcraft.energy.block;

import buildcraft.energy.tile.TileDynamoMJ;
import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.internal.block.ICustomRotationHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** MJ -> Forge Energy converter block. */
public class BlockDynamoMJ extends BlockBCTile_Neptune implements EntityBlock, ICustomRotationHandler {
    private static final VoxelShape UP = Shapes.or(
        Block.box(0, 0, 0, 16, 4, 16), Block.box(4, 4, 4, 12, 16, 12)
    );
    private static final VoxelShape DOWN = Shapes.or(
        Block.box(0, 12, 0, 16, 16, 16), Block.box(4, 0, 4, 12, 12, 12)
    );
    private static final VoxelShape EAST = Shapes.or(
        Block.box(0, 0, 0, 4, 16, 16), Block.box(4, 4, 4, 16, 12, 12)
    );
    private static final VoxelShape WEST = Shapes.or(
        Block.box(12, 0, 0, 16, 16, 16), Block.box(0, 4, 4, 12, 12, 12)
    );
    private static final VoxelShape SOUTH = Shapes.or(
        Block.box(0, 0, 0, 16, 16, 4), Block.box(4, 4, 4, 12, 12, 16)
    );
    private static final VoxelShape NORTH = Shapes.or(
        Block.box(0, 0, 12, 16, 16, 16), Block.box(4, 4, 0, 12, 12, 12)
    );

    public BlockDynamoMJ(Properties properties) {
        super(properties);
    }

    @Override
    public void neighborChanged(BlockState state, Level world, BlockPos pos, Block block, BlockPos fromPos, boolean moving) {
        super.neighborChanged(state, world, pos, block, fromPos, moving);
        if (world.isClientSide) return;
        BlockEntity tile = world.getBlockEntity(pos);
        if (tile instanceof TileDynamoMJ dynamo) {
            dynamo.rotateIfInvalid();
        }
    }

    @Override
    public InteractionResult attemptRotation(Level world, BlockPos pos, BlockState state, Direction sideWrenched) {
        BlockEntity tile = world.getBlockEntity(pos);
        return tile instanceof TileDynamoMJ dynamo ? dynamo.attemptRotation() : InteractionResult.FAIL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TileDynamoMJ(pos, state);
    }

    @Override public boolean hasDynamicShape() { return true; }
    @Override public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) { return 1.0F; }
    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }
    @Override public boolean useShapeForLightOcclusion(BlockState state) { return false; }
    @Override public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return Shapes.empty(); }
    @Override public VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) { return Shapes.empty(); }
    @Override public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return getEngineShape(level, pos); }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return getEngineShape(level, pos); }

    private static VoxelShape getEngineShape(BlockGetter level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        Direction direction = blockEntity instanceof TileDynamoMJ dynamo ? dynamo.getCurrentFacing() : Direction.UP;
        if (direction == null) direction = Direction.UP;
        return switch (direction) {
            case DOWN -> DOWN;
            case EAST -> EAST;
            case WEST -> WEST;
            case SOUTH -> SOUTH;
            case NORTH -> NORTH;
            default -> UP;
        };
    }
}
