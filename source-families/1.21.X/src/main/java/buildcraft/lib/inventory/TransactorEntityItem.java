//? source if >=1.21.11
/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.IStackFilter;
import buildcraft.lib.internal.inventory.IItemTransactor.IItemExtractable;
import buildcraft.lib.misc.StackUtil;

import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

public class TransactorEntityItem implements IItemExtractable {

    private final ItemEntity entity;

    public TransactorEntityItem(ItemEntity entity) {
        this.entity = entity;
    }

    @Nonnull
    public ItemStack extract(IStackFilter filter, int min, int max, boolean simulate) {
        if (entity.isRemoved()) {
            return StackUtil.EMPTY;
        }
        if (min < 1) {
            min = 1;
        }
        if (max < min) {
            return StackUtil.EMPTY;
        }
        boolean transactional = buildcraft.lib.compat.transfer.TransferJournal.active();
        var state = transactional ? buildcraft.lib.compat.transfer.EntityTransferStorage.item(entity) : null;
        ItemStack current = transactional ? state.get() : entity.getItem();
        if (current.isEmpty() || current.getCount() < min) {
            return StackUtil.EMPTY;
        }
        if (filter.matches(current)) {
            current = current.copy();
            ItemStack extracted = current.split(max);
            if (!simulate) {
                if (transactional) {
                    state.set(current);
                } else if (current.getCount() == 0) {
                    entity.setRemoved(RemovalReason.DISCARDED);
                } else {
                    entity.setItem(current);
                }
            }
            return extracted;
        } else {
            return StackUtil.EMPTY;
        }
    }

    public String toString() {
        return entity.toString();
    }
}
