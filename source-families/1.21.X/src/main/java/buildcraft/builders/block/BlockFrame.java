/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.block;

import java.util.List;
import java.util.Map;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.builders.BCBuildersBlocks;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.transport.block.BlockPipeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.21.5 {
import net.minecraft.util.RandomSource;
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
//? if >=1.21.5 {
import net.minecraft.world.level.ScheduledTickAccess;
//?}
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BlockFrame extends BlockBCBase_Neptune {
    public static final Map<Direction, BooleanProperty> CONNECTED_MAP = BuildCraftProperties.CONNECTED_MAP;

    public static final VoxelShape BASE_AABB = BlockPipeHolder.BOX_CENTER;
    public static final VoxelShape[] CONNECTION_AABB = BlockPipeHolder.BOX_FACES;
    

    public BlockFrame() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).sound(SoundType.STONE).strength(0.25f)
                .forceSolidOn()
				.explosionResistance(3.0f));
        this.registerDefaultState(this.stateDefinition.any()
        		.setValue(BuildCraftProperties.CONNECTED_DOWN, false)
            	.setValue(BuildCraftProperties.CONNECTED_EAST, false)
            	.setValue(BuildCraftProperties.CONNECTED_NORTH, false)
            	.setValue(BuildCraftProperties.CONNECTED_WEST, false)
            	.setValue(BuildCraftProperties.CONNECTED_SOUTH, false)
            	.setValue(BuildCraftProperties.CONNECTED_UP, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> bs) {
    	BuildCraftProperties.CONNECTED_MAP.values().forEach(bs::add);
    	super.createBlockStateDefinition(bs);
    }
    
    

    @Override
	public BlockState getStateForPlacement(BlockPlaceContext bpc) {
    	BlockState state = defaultBlockState();
        for (Direction side : CONNECTED_MAP.keySet()) {
            Block block = bpc.getLevel().getBlockState(bpc.getClickedPos().offset(side.getNormal())).getBlock();
            state = state.setValue(CONNECTED_MAP.get(side), block instanceof BlockFrame || block instanceof BlockQuarry);
        }
        return state;
	}

    //? if >=1.21.5 {
    /**
     * Minecraft 1.21.11 no longer drives this regular block through the legacy
     * neighborChanged signature. The quarry places frame blocks one at a time, so
     * without this callback only the newly placed frame sees its existing neighbour;
     * the existing frame never gains the reciprocal connection and the structure is
     * rendered as separated centre cubes with gaps between them.
     */
    @Override
    protected BlockState updateShape(BlockState state, LevelReader world, ScheduledTickAccess scheduledTickAccess,
            BlockPos pos, Direction direction, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        Block neighbour = neighbourState.getBlock();
        return state.setValue(CONNECTED_MAP.get(direction),
            neighbour instanceof BlockFrame || neighbour instanceof BlockQuarry);
    }
    //?} else {
	@Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
			BlockPos fromPos, boolean p_60514_) {
		Block b = level.getBlockState(fromPos).getBlock();
		BlockPos delta = fromPos.subtract(pos);
		Direction d = Direction.fromDelta(delta.getX(), delta.getY(), delta.getZ());
		level.setBlockAndUpdate(pos, state.setValue(CONNECTED_MAP.get(d), b instanceof BlockFrame || b instanceof BlockQuarry));
	}
    //?}

	@Override
	public boolean isCollisionShapeFullBlock(BlockState p_181242_, BlockGetter p_181243_, BlockPos p_181244_) {
		return false;
	}

	@Override
	public boolean isOcclusionShapeFullBlock(BlockState p_222959_, BlockGetter p_222960_, BlockPos p_222961_) {
		return false;
	}
	
	@Override
	public boolean propagatesSkylightDown(BlockState p_49928_, BlockGetter p_49929_, BlockPos p_49930_) {
		return false;
	}

	
	
    @Override
	public boolean skipRendering(BlockState state, BlockState other, Direction side) {
        Direction[] facings = CONNECTED_MAP.keySet().stream()
                .filter(facing -> state.getValue(CONNECTED_MAP.get(facing)))
                .toArray(Direction[]::new);
        if (facings.length == 1) {
            return side == facings[0];
        } else if (facings.length == 2 && facings[0] == facings[1].getOpposite()) {
            return side == facings[0] || side == facings[1];
        }
        return false;
	}
    
    
    
    @Override
	public VoxelShape getShape(BlockState state, BlockGetter p_60556_, BlockPos p_60557_,
			CollisionContext p_60558_) {
    	Direction[] dirs = Direction.values();
    	Direction[] result = new Direction[6];
    	int len = 0;
    	for(int i = 0;i < 6;i++) {
    		if(state.getValue(CONNECTED_MAP.get(dirs[i])))
    			result[len++] = dirs[i]; 
    	}
    	return BlockPipeHolder.getCachedPipeShape(result, len);
	}

    @Override
	public List<ItemStack> getDrops(BlockState state, Builder builder) {
		return List.of();
	}
    


}
