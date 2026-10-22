/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.block;

import buildcraft.factory.tile.TileMiner;
import buildcraft.factory.tile.TilePump;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.misc.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
//? if >=1.20 {
/*?
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.material.MapColor;
?*/
//?}
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
//? if <1.20 {
import net.minecraft.world.level.material.Material;
//?}
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class BlockTube extends BlockBCBase_Neptune {
    private static final VoxelShape BOUNDING_BOX = Block.box(4D, 0D, 4D, 12D, 16D, 12D);

    public BlockTube() {
        //? if <1.20 {
        super(BlockBehaviour.Properties.of(Material.METAL).strength(-1.0F, 3600000.0F).noLootTable());
        //?} else {
        /*?
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(-1.0F, 3600000.0F).noLootTable());
        ?*/
        //?}
    }

    @Override
	public boolean isCollisionShapeFullBlock(BlockState p_181242_, BlockGetter p_181243_, BlockPos p_181244_) {
    	return false;
    }

	@Override
	public boolean isOcclusionShapeFullBlock(BlockState p_222959_, BlockGetter p_222960_, BlockPos p_222961_) {
		return false;
	}
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level world, BlockPos pos, Player player, boolean willHarvest,
            FluidState fluid) {
        BlockPos currentPos = pos.above();
        while (currentPos.getY() < world.getMaxBuildHeight()
                && world.getBlockState(currentPos).getBlock() == this) {
            currentPos = currentPos.above();
        }
        if (!(world.getBlockEntity(currentPos) instanceof TileMiner)) {
            return super.onDestroyedByPlayer(state, world, pos, player, willHarvest, fluid);
        }
        return false;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter source, BlockPos pos,
			CollisionContext context) {
		return BOUNDING_BOX;
	}

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
            BlockPos fromPos, boolean moving) {
        if (pos.getY() - 1 == fromPos.getY()
                && BlockUtil.getFluid(block) != Fluids.EMPTY
                && block != level.getBlockState(fromPos).getBlock()) {
            BlockPos currentPos = pos.above();
            while (currentPos.getY() < level.getMaxBuildHeight()) {
                BlockEntity blockEntity = level.getBlockEntity(currentPos);
                if (blockEntity instanceof TilePump pump) {
                    pump.neighbourBlockChanged(level.getBlockState(currentPos), fromPos, true);
                    break;
                }
                if (blockEntity instanceof TileMiner) {
                    break;
                }
                if (level.getBlockState(currentPos).getBlock() != this) {
                    break;
                }
                currentPos = currentPos.above();
            }
        }
        super.neighborChanged(state, level, pos, block, fromPos, moving);
    }



}
