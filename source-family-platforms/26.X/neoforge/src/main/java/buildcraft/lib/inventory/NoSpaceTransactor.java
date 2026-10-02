/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.inventory.IItemTransactor;
import buildcraft.lib.misc.StackUtil;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

public enum NoSpaceTransactor implements IItemTransactor {
    INSTANCE;

    @Nonnull
    public ItemStack insert(@Nonnull ItemStack stack, boolean allOrNone, boolean simulate) {
        return stack;
    }

    public NonNullList<ItemStack> insert(NonNullList<ItemStack> stacks, boolean simulate) {
        return stacks;
    }

    @Nonnull
    public ItemStack extract(IStackFilter filter, int min, int max, boolean simulate) {
        return StackUtil.EMPTY;
    }
}