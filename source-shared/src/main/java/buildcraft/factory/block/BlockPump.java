/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.factory.block;

import buildcraft.factory.tile.TilePump;
import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.block.RedstoneModuleState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;


public class BlockPump extends BlockBCTile_Neptune implements EntityBlock {

    public BlockPump() {
        super();
        registerDefaultState(defaultBlockState().setValue(RedstoneModuleState.INSTALLED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(RedstoneModuleState.INSTALLED);
        super.createBlockStateDefinition(builder);
    }


	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TilePump(pos,state);
	}

	
}
