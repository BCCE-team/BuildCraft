/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.block;

import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.transport.tile.TileFilteredBuffer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class BlockFilteredBuffer extends BlockBCTile_Neptune {
    public BlockFilteredBuffer() {
        super();
    }

	@Override
	public TileBC_Neptune newBlockEntity(BlockPos p_153215_, BlockState p_153216_) {
		return new TileFilteredBuffer(p_153215_, p_153216_);
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
                && world.getBlockEntity(pos) instanceof TileFilteredBuffer tile) {
            serverPlayer.openMenu(tile, buffer -> buffer.writeBlockPos(pos));
        }
    }
    


}
