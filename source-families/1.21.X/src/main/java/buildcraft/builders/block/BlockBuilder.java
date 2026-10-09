/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.builders.block;

import buildcraft.lib.internal.enums.EnumOptionalSnapshotType;
import buildcraft.lib.internal.properties.BuildCraftProperties;
import buildcraft.builders.tile.TileBuilder;
import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.block.IBlockWithFacing;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.phys.BlockHitResult;

public class BlockBuilder extends BlockBCTile_Neptune implements IBlockWithFacing {
    public static final EnumProperty<EnumOptionalSnapshotType> SNAPSHOT_TYPE = BuildCraftProperties.SNAPSHOT_TYPE;

    public BlockBuilder() {
        super();
        registerDefaultState(stateDefinition.any().setValue(SNAPSHOT_TYPE, EnumOptionalSnapshotType.NONE));
    }

    // BlockState

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> bs) {
		bs.add(SNAPSHOT_TYPE);
		super.createBlockStateDefinition(bs);
	}
	
		
    @Override
	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(world, pos, state, placer, stack);
		BlockEntity tile = world.getBlockEntity(pos);
		if (tile instanceof TileBuilder builder) {
			world.setBlockAndUpdate(pos,state.setValue(SNAPSHOT_TYPE,
							EnumOptionalSnapshotType.fromNullable((builder.snapshotType))));
		}
	}
    
    @Override
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
			boolean p_60514_) {
		BlockEntity tile = level.getBlockEntity(pos);
		if (tile instanceof TileBuilder builder) {
			level.setBlockAndUpdate(pos,state.setValue(SNAPSHOT_TYPE,
							EnumOptionalSnapshotType.fromNullable((builder.snapshotType))));
		}
		super.neighborChanged(state, level, pos, block, fromPos, p_60514_);
	}
    
    // Others

    @Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TileBuilder(pos, state);
	}

    
    
    @Override
    protected ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state, Level world,
            BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        openMenu(world, pos, player);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
            BlockHitResult hit) {
        openMenu(world, pos, player);
        return InteractionResult.SUCCESS;
    }

    private static void openMenu(Level world, BlockPos pos, Player player) {
        if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer
                && world.getBlockEntity(pos) instanceof TileBuilder tile) {
            serverPlayer.openMenu(tile, buffer -> buffer.writeBlockPos(pos));
        }
    }
	
	

    @Override
    public boolean canBeRotated(LevelAccessor world, BlockPos pos, BlockState state) {
        BlockEntity tile = world.getBlockEntity(pos);
        return !(tile instanceof TileBuilder) || ((TileBuilder) tile).getBuilder() == null;
    }
    
	@Override
	public <T extends BlockEntity> GameEventListener getListener(ServerLevel level, T tile) {
		return tile instanceof TileBuilder be ? be.worldEventListener :null;
	}
}
