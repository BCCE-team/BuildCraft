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
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public class BlockConstructionMarker extends BlockMarkerBase {
    public BlockConstructionMarker() {
        super(Properties.of().mapColor(MapColor.NONE).noOcclusion().lightLevel(state -> 8));
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TileConstructionMarker(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack held, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (!(tile instanceof TileConstructionMarker marker)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (TileConstructionMarker.isValidBlueprint(held)) {
            if (!world.isClientSide() && marker.setBlueprintFromPlayer(held, player) && !player.isCreative()) {
                held.shrink(1);
            }
            return ItemInteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown() && WrenchUtil.isWrench(held)) {
            if (!world.isClientSide()) {
                marker.ejectBlueprint(player);
            }
            return ItemInteractionResult.SUCCESS;
        }

        // A held ordinary item may continue to its own useOn(), but must not be
        // redirected into the empty-hand block callback below.
        return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
            BlockHitResult hit) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (!(tile instanceof TileConstructionMarker marker)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            if (!world.isClientSide()) {
                marker.ejectBlueprint(player);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }
}
