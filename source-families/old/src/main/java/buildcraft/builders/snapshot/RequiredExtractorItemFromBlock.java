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

import buildcraft.lib.world.SingleBlockAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class RequiredExtractorItemFromBlock extends RequiredExtractor {
    @Nonnull
    @Override
    public List<ItemStack> extractItemsFromBlock(@Nonnull BlockState blockState, @Nullable CompoundTag tileNbt,
        Level level) {
        SingleBlockAccess access = new SingleBlockAccess(blockState);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(BlockPos.ZERO), Direction.UP, BlockPos.ZERO, false);
        ItemStack result = blockState.getBlock().getCloneItemStack(
            blockState,
            hit,
            access,
            BlockPos.ZERO,
            null
        );
        if (result.isEmpty()) {
            result = new ItemStack(blockState.getBlock().asItem());
        }
        return Collections.singletonList(result);
    }
}
