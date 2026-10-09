//? source if >=1.21.11
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.lib.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.ImmutableList;

import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams.Builder;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.redstone.Orientation;

public abstract class BlockBCTile_Neptune extends BlockBCBase_Neptune implements EntityBlock {

	
    public BlockBCTile_Neptune(Properties material) {
        super(material);
    }
    
    public BlockBCTile_Neptune() {}

	public void wasExploded(Level world, BlockPos pos, Explosion explosion) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune) {
            TileBC_Neptune tileBC = (TileBC_Neptune) tile;
            tileBC.onExplode(explosion);
        }
	}

	public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        BlockEntity tile = level.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune) {
            TileBC_Neptune tileBC = (TileBC_Neptune) tile;
            tileBC.onRemove(true);
        }
	}
    

	public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, ItemStack toolStack, boolean willHarvest, FluidState fluid) {
        BlockEntity tile = level.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune) {
            TileBC_Neptune tileBC = (TileBC_Neptune) tile;

            // Vanilla skips block loot entirely when a creative-mode player breaks a block. BuildCraft machines are
            // different: their internal inventories/tanks are real world contents, and BC7/BC8 always returned them.
            // Drop only the internal contents here; the block item itself remains suppressed by creative mode.
            if (!level.isClientSide() && player.isCreative()) {
                NonNullList<ItemStack> contents = NonNullList.create();
                tileBC.addDrops(contents, UPDATE_ALL);
                Containers.dropContents(level, pos, contents);
            }

            tileBC.onRemove(!player.isCreative() && willHarvest);
        }
		return super.onDestroyedByPlayer(state, level, pos, player, toolStack, willHarvest, fluid);
	}

	public void setPlacedBy(Level world, BlockPos pos, BlockState state, LivingEntity placer,
			ItemStack stack) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune) {
            TileBC_Neptune tileBC = (TileBC_Neptune) tile;
            tileBC.onPlacedBy(placer, stack);
            tileBC.onNeighbourBlockChanged(Blocks.AIR.defaultBlockState(), pos);
            tileBC.neighbourBlockChanged(Blocks.AIR.defaultBlockState(), pos, false);
        }
		super.setPlacedBy(world, pos, state, placer, stack);
	}
    
	public List<ItemStack> getDrops(BlockState state, Builder builder) {
		BlockEntity blockEntity = builder.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
		NonNullList<ItemStack> drops = NonNullList.create();
		if(blockEntity instanceof TileBC_Neptune tile) {
			tile.addDrops(drops, UPDATE_ALL);
		}
        ItemStack blockStack = ItemStack.EMPTY;
        if (blockEntity != null && blockEntity.getLevel() != null) {
            // Preserve state-sensitive pick/clone semantics (for example the shared BC8 engine block).
            blockStack = state.getBlock().getCloneItemStack(
                blockEntity.getLevel(), blockEntity.getBlockPos(), state, false, null
            );
        }
        if (blockStack.isEmpty()) {
            blockStack = new ItemStack(state.getBlock().asItem());
        }
        if (!blockStack.isEmpty()) {
            drops.add(blockStack);
        }
        return drops;
	}

    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        InteractionResult result = activateTile(world, pos, player, hand, hit);
        return result;
    }

    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
            BlockHitResult hit) {
        return activateTile(world, pos, player, InteractionHand.MAIN_HAND, hit);
    }

    private InteractionResult activateTile(Level world, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune tileBC) {
            return tileBC.onActivated(player, hand, hit);
        }
        return InteractionResult.PASS;
    }



    /**
     * 1.21.11 moved ordinary neighbour shape updates to the extended updateShape signature while BuildCraft's
     * legacy neighborChanged bridge no longer overrides the vanilla callback. Route that real callback back into
     * the two tile hooks so topology, redstone state and endpoint orientation are refreshed for non-pipe blocks too.
     */
    protected BlockState updateShape(BlockState state, LevelReader world, ScheduledTickAccess scheduledTickAccess,
            BlockPos pos, Direction direction, BlockPos neighbourPos, BlockState neighbourState, RandomSource random) {
        if (world instanceof Level level && !level.isClientSide()) {
            BlockEntity tile = level.getBlockEntity(pos);
            if (tile instanceof TileBC_Neptune tileBC) {
                tileBC.onNeighbourBlockChanged(state, neighbourPos);
                tileBC.neighbourBlockChanged(state, neighbourPos, false);
            }
        }
        return super.updateShape(state, world, scheduledTickAccess, pos, direction, neighbourPos, neighbourState, random);
    }

    /** 1.21.11 vanilla neighbour callback. Keep the legacy overload below for subclasses that still call it. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
            Orientation orientation, boolean harvest) {
        BlockPos fromPos = orientation == null ? pos : pos.relative(orientation.getFront());
        BlockEntity tile = level.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune tileBC) {
            tileBC.neighbourBlockChanged(state, fromPos, harvest);
        }
        super.neighborChanged(state, level, pos, neighbor, orientation, harvest);
    }

	@SuppressWarnings("deprecation")
	public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor,
			BlockPos fromPos, boolean harvest) {
        BlockEntity tile = level.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune) {
            TileBC_Neptune tileBC = (TileBC_Neptune) tile;
            tileBC.neighbourBlockChanged(state, fromPos, harvest);
        }

	}
	
	//Only Update for tileEntity changed
	public void onNeighborChange(BlockState state, LevelReader level, BlockPos pos, BlockPos neighbor) {
        BlockEntity tile = level.getBlockEntity(pos);
        if (tile instanceof TileBC_Neptune) {
            TileBC_Neptune tileBC = (TileBC_Neptune) tile;
            tileBC.onNeighbourBlockChanged(state, neighbor);
        }
		super.onNeighborChange(state, level, pos, neighbor);
	}

	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
			BlockEntityType<T> bet) {
		return (a,b,c,blockEntity) -> {
			if(blockEntity instanceof TileBC_Neptune tile) {
				tile.update();
			}
		};
	}
	
	
    
    
}
