/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.misc.StackUtil;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public final class InventoryWrapper extends AbstractInvItemTransactor {
    private final Container Container;

    public InventoryWrapper(Container Container) {
        this.Container = Container;
    }

    @Nonnull
    protected ItemStack insert(int slot, @Nonnull ItemStack stack, boolean simulate) {
        ItemStack current = Container.getItem(slot);
        if (!Container.canPlaceItem(slot, stack)) {
            return stack;
        }
        if (current.isEmpty()) {
            int max = Math.min(Container.getMaxStackSize(), stack.getMaxStackSize());
            ItemStack split = stack.split(max);
            if (!simulate) {
                Container.setItem(slot, split);
            }
            if (stack.isEmpty()) {
                return StackUtil.EMPTY;
            } else {
                return stack;
            }
        }
        if (StackUtil.canMerge(current, stack)) {
            int max = Math.min(Container.getMaxStackSize(), current.getMaxStackSize());
            int space = max - current.getCount();
            if (space <= 0) {
                return stack;
            }

            int toMove = Math.min(space, stack.getCount());
            ItemStack remaining = stack.copy();
            remaining.shrink(toMove);

            if (!simulate) {
                ItemStack merged = current.copy();
                merged.grow(toMove);
                Container.setItem(slot, merged);
            }

            return remaining.isEmpty() ? StackUtil.EMPTY : remaining;
        }
        return stack;
    }

    @Nonnull
    protected ItemStack extract(int slot, IStackFilter filter, int min, int max, boolean simulate) {
        ItemStack current = Container.getItem(slot);
        if (current.isEmpty()) {
            return StackUtil.EMPTY;
        }
        if (filter.matches(current.copy())) {
            if (current.getCount() < min) {
                return StackUtil.EMPTY;
            }
            int size = Math.min(current.getCount(), max);
            current = current.copy();
            ItemStack other = current.split(size);
            if (!simulate) {
                if (current.getCount() <= 0) {
                    current = StackUtil.EMPTY;
                }
                Container.setItem(slot, current);
            }
            return other;
        } else {
            return StackUtil.EMPTY;
        }
    }

    protected int getSlots() {
        return Container.getContainerSize();
    }

    protected boolean isEmpty(int slot) {
        return Container.getItem(slot).isEmpty();
    }
}
