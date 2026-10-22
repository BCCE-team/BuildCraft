/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.builders.snapshot;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public class RequiredExtractorItemFromBlock extends RequiredExtractor {
    @Nonnull
    @Override
    public List<ItemStack> extractItemsFromBlock(@Nonnull BlockState blockState, @Nullable CompoundTag tileNbt,
        Level level) {
        // The modern overload requires a LevelReader. We have no real placement position here,
        // so use the state-aware overload instead of passing a fake BlockGetter or null context.
        //? if >=1.21.4 {
        ItemStack result = new ItemStack(blockState.getBlock().asItem());
        //?} else {
        ItemStack result = blockState.getBlock().getCloneItemStack(level, BlockPos.ZERO, blockState);
        //?}
        if (result.isEmpty()) {
            result = new ItemStack(blockState.getBlock().asItem());
        }
        return Collections.singletonList(result);
    }
}
