/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.crops;

import buildcraft.api.v2.crops.CropAdapter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public enum CropHandlerReeds implements CropAdapter {
    INSTANCE;
    public static final int MAX_HEIGHT = 3;

    @Override
    public boolean isSeed(ItemStack stack) {
        return stack.getItem() == Items.SUGAR_CANE;
    }

    @Override
    public boolean canSustainPlant(Level world, ItemStack seed, BlockPos pos) {
        BlockPos plantPos = pos.above();
        return world.getBlockState(pos).getBlock() != Blocks.SUGAR_CANE
            && world.isEmptyBlock(plantPos)
            && Blocks.SUGAR_CANE.defaultBlockState().canSurvive(world, plantPos);
    }

    @Override
    public boolean plant(Level world, Player player, ItemStack seed, BlockPos pos) {
        return CropHandlerPlantable.INSTANCE.plant(world, player, seed, pos);
    }

    @Override
    public boolean isMature(BlockGetter access, BlockState state, BlockPos pos) {
        return state.getBlock() == Blocks.SUGAR_CANE
            && access.getBlockState(pos.below()).getBlock() == Blocks.SUGAR_CANE;
    }

    @Override
    public boolean harvest(Level world, BlockPos pos, NonNullList<ItemStack> drops, Player actor) {
        if (!isMature(world, world.getBlockState(pos), pos)) {
            return false;
        }
        return CropHandlerPlantable.INSTANCE.harvest(world, pos, drops, actor);
    }
}
