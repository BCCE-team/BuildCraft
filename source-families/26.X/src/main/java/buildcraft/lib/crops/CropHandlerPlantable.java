/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.crops;

import buildcraft.api.v2.crops.CropAdapter;
import buildcraft.lib.misc.BlockUtil;
import buildcraft.lib.misc.FakePlayerProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.MushroomBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.SpecialPlantable;

public enum CropHandlerPlantable implements CropAdapter {
    INSTANCE;

    public boolean isSeed(ItemStack stack) {
        if (stack.getItem() instanceof SpecialPlantable) {
            return true;
        }
        if (stack.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            return block instanceof BushBlock && block != Blocks.SUGAR_CANE;
        }
        return false;
    }

    public boolean canSustainPlant(Level world, ItemStack seed, BlockPos pos) {
        BlockPos plantPos = pos.above();
        if (!world.isEmptyBlock(plantPos)) {
            return false;
        }
        if (seed.getItem() instanceof SpecialPlantable specialPlantable) {
            return specialPlantable.canPlacePlantAtPosition(seed, world, plantPos, Direction.UP);
        }
        if (seed.getItem() instanceof BlockItem blockItem) {
            Block plant = blockItem.getBlock();
            return plant != world.getBlockState(pos).getBlock()
                && plant.defaultBlockState().canSurvive(world, plantPos);
        }
        return false;
    }

    public boolean plant(Level world, Player player, ItemStack seed, BlockPos pos) {
        return BlockUtil.useItemOnBlock(world, player, seed, pos, Direction.UP);
    }

    public boolean isMature(BlockGetter blockAccess, BlockState state, BlockPos pos) {
        Block block = state.getBlock();
        if (block instanceof FlowerBlock || block instanceof TallGrassBlock || block instanceof MushroomBlock || block instanceof DoublePlantBlock
            || block == Blocks.MELON || block == Blocks.PUMPKIN) {
            return true;
        } else if (block instanceof CropBlock) {
            return ((CropBlock) block).isMaxAge(state);
        } else if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) == 3;
        } else if (block instanceof BushBlock) {
            if (blockAccess.getBlockState(pos.below()).getBlock() == block) {
                return true;
            }
        }
        return false;
    }

    public boolean harvest(Level world, BlockPos pos, NonNullList<ItemStack> drops, Player actor) {
        if (!(world instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        var owner = actor == null ? FakePlayerProvider.NULL_PROFILE : actor.getGameProfile();
        var harvested = BlockUtil.breakBlockAndGetDrops(serverLevel, pos, ItemStack.EMPTY, owner);
        if (harvested.isEmpty()) {
            return false;
        }
        drops.addAll(harvested.get());
        serverLevel.levelEvent(null, 2001, pos, Block.getId(state));
        return true;
    }

}
