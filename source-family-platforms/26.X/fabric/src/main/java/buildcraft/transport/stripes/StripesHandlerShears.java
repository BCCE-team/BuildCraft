/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.transport.stripes;

import java.util.List;

import buildcraft.api.v2.automation.StripesOutput;
import buildcraft.lib.misc.BlockUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.entity.BlockEntity;

public enum StripesHandlerShears {
    INSTANCE;

    public boolean handle(Level world,
                          BlockPos pos,
                          Direction direction,
                          ItemStack stack,
                          Player player,
                          StripesOutput activator) {
        if (!(stack.getItem() instanceof ShearsItem)) {
            return false;
        }

        pos = pos.offset(direction.getUnitVec3i());
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();

        // Vanilla drop logic respects the shears tool and block loot tables.
        // Fabric datapacks/custom blocks may add their own shears loot entries.
        if (!state.is(BlockTags.LEAVES) && !state.is(Blocks.COBWEB) &&
            !state.is(BlockTags.WOOL) && !state.is(Blocks.VINE) &&
            !state.is(Blocks.SHORT_GRASS) && !state.is(Blocks.TALL_GRASS) &&
            !state.is(Blocks.FERN) && !state.is(Blocks.LARGE_FERN) &&
            !state.is(Blocks.DEAD_BUSH)) return false;
        if (!(world instanceof ServerLevel serverLevel) ||
            !BlockUtil.canBreakBlock(serverLevel, pos, player)) return false;
        // Harvest before removing the block, so loot conditions see the original state.
        BlockEntity blockEntity = world.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity, player, stack);
        if (!world.setBlock(pos, Blocks.AIR.defaultBlockState(), 11)) return false;
        stack.hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        for (ItemStack drop : drops) if (!drop.isEmpty()) activator.sendItem(drop, direction);
        return true;
    }
}
