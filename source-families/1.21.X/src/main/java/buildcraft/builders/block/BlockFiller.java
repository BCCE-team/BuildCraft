/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.block;

import buildcraft.builders.tile.TileFiller;
import buildcraft.lib.block.BlockBCTile_Neptune;
import buildcraft.lib.block.IBlockWithFacing;
import buildcraft.lib.tile.TileBC_Neptune;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class BlockFiller extends BlockBCTile_Neptune implements IBlockWithFacing {

    // Others

    @Override
	public TileBC_Neptune newBlockEntity(BlockPos pos, BlockState state) {
		return new TileFiller(pos, state);
	}

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        openMenu(world, pos, player);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
            BlockHitResult hit) {
        return openMenu(world, pos, player);
    }

    private static InteractionResult openMenu(Level world, BlockPos pos, Player player) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (!(tile instanceof TileFiller filler)) {
            return InteractionResult.PASS;
        }
        if (!world.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            if (!filler.hasBox()) {
                filler.refreshAreaFromMarkers(player);
            }
            serverPlayer.openMenu(filler, buffer -> buffer.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(world.isClientSide());
    }

    @Override
    public boolean canBeRotated(LevelAccessor world, BlockPos pos, BlockState state) {
        return false;
    }
}
