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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if <1.20 {
import net.minecraft.world.level.material.Material;
//?} else {
/*?
import net.minecraft.world.level.material.MapColor;
?*/
//?}
import net.minecraft.world.phys.BlockHitResult;

public class BlockMarkerPath extends BlockMarkerBase {
    public BlockMarkerPath() {
        //? if <1.20 {
        super(Properties.of(Material.DECORATION));
        //?} else {
        /*?
        super(Properties.of().mapColor(MapColor.NONE));
        ?*/
        //?}
    }
    
    @Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new TileMarkerPath(pos, state);
	}

	@Override
	public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand,
			BlockHitResult hit) {
        if (BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).adapter(player.getItemInHand(hand)).isPresent()) {
            return InteractionResult.PASS;
        }
        if (!world.isClientSide) {
            BlockEntity tile = world.getBlockEntity(pos);
            if (tile instanceof TileMarkerPath) {
                TileMarkerPath marker = (TileMarkerPath) tile;
                if (PermissionUtil.hasPermission(PermissionUtil.PERM_EDIT, player, marker.getPermBlock())) {
                    marker.reverseDirection();
                }
            }
        }
        return InteractionResult.SUCCESS;
	}

}
