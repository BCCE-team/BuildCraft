/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.block;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.core.blockEntity.TileMarkerPath;
import buildcraft.lib.block.BlockMarkerBase;
import buildcraft.lib.misc.PermissionUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public class BlockMarkerPath extends BlockMarkerBase {
    public BlockMarkerPath() {
        super(Properties.of().mapColor(MapColor.NONE));
    }
    
    @Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TileMarkerPath(pos, state);
	}
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).adapter(stack).isPresent()) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        activate(world, pos, player);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
            BlockHitResult hit) {
        activate(world, pos, player);
        return InteractionResult.SUCCESS;
    }

    private static void activate(Level world, BlockPos pos, Player player) {
        if (!world.isClientSide) {
            BlockEntity tile = world.getBlockEntity(pos);
            if (tile instanceof TileMarkerPath marker
                && PermissionUtil.hasPermission(PermissionUtil.PERM_EDIT, player, marker.getPermBlock())) {
                marker.reverseDirection();
            }
        }
    }

}
