//? source if >=1.21.1
/* Copyright (c) 2016 SpaceToad and the BuildCraft team
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.core.block;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.core.blockEntity.TileMarkerVolume;
import buildcraft.lib.block.BlockMarkerBase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;

public class BlockMarkerVolume extends BlockMarkerBase {
    public BlockMarkerVolume() {
        super(Properties.of().mapColor(MapColor.NONE));
    }

    public BlockEntity newBlockEntity(BlockPos p_153215_, BlockState p_153216_) {
        return new TileMarkerVolume(p_153215_, p_153216_);
    }


    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
        Orientation orientation, boolean movedByPiston) {
        checkSignalState(level, pos);
    }


    public void tick(BlockState state, ServerLevel world, BlockPos pos, RandomSource random) {
        checkSignalState(world, pos);
    }

    private static void checkSignalState(Level world, BlockPos pos) {
        if (world.isClientSide()) {
            return;
        }
        BlockEntity tile = world.getBlockEntity(pos);
        if (tile instanceof TileMarkerVolume) {
            TileMarkerVolume volume = (TileMarkerVolume) tile;
            volume.refreshSignalStateFromWorld();
        }
    }
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level world, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (BuildCraftApi.service(BuildCraftServices.MAP_LOCATIONS).adapter(stack).isPresent()) {
            return InteractionResult.PASS;
        }
        activate(world, pos, player);
        return InteractionResult.SUCCESS;
    }

    protected InteractionResult useWithoutItem(BlockState state, Level world, BlockPos pos, Player player,
            BlockHitResult hit) {
        activate(world, pos, player);
        return InteractionResult.SUCCESS;
    }

    private static void activate(Level world, BlockPos pos, Player player) {
        if (!world.isClientSide()) {
            BlockEntity tile = world.getBlockEntity(pos);
            if (tile instanceof TileMarkerVolume volume) {
                volume.onManualConnectionAttempt(player);
            }
        }
    }

}
