/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.builders.block;

import buildcraft.builders.tile.TileConstructionMarker;
import buildcraft.lib.misc.WrenchUtil;
import buildcraft.lib.block.BlockMarkerBase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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

public class BlockConstructionMarker extends BlockMarkerBase {
    public BlockConstructionMarker() {
        //? if <1.20 {
        super(Properties.of(Material.DECORATION).noOcclusion().lightLevel(state -> 8));
        //?} else {
        /*?
        super(Properties.of().mapColor(MapColor.NONE).noOcclusion().lightLevel(state -> 8));
        ?*/
        //?}
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TileConstructionMarker(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (!(tile instanceof TileConstructionMarker marker)) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);
        if (TileConstructionMarker.isValidBlueprint(held)) {
            if (!world.isClientSide && marker.setBlueprintFromPlayer(held, player)) {
                if (!player.isCreative()) {
                    held.shrink(1);
                }
            }
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown() && (held.isEmpty() || WrenchUtil.isWrench(held))) {
            if (!world.isClientSide) {
                marker.ejectBlueprint(player);
            }
            return InteractionResult.SUCCESS;
        }

        // Match the modern interaction contract: ordinary held items are
        // allowed to continue to their own Item#useOn implementation.
        return InteractionResult.PASS;
    }
}
