/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.tile.item;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.NotNull;

import buildcraft.lib.internal.inventory.IItemHandlerFiltered;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

public class DelegateItemHandler implements IItemHandlerModifiable, IItemHandlerFiltered {
    private final IItemHandlerModifiable delegate;
    

    public DelegateItemHandler(IItemHandlerModifiable delegate) {
        this.delegate = delegate;
    }

    public int getSlots() {
        return delegate.getSlots();
    }

    public ItemStack getStackInSlot(int slot) {
        return delegate.getStackInSlot(slot);
    }

    public ItemStack insertItem(int slot, @Nonnull ItemStack stack, boolean simulate) {
        return delegate.insertItem(slot, stack, simulate);
    }

    @Nonnull
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        return delegate.extractItem(slot, amount, simulate);
    }

    public void setStackInSlot(int slot, @Nonnull ItemStack stack) {
        delegate.setStackInSlot(slot, stack);
    }

    public int getSlotLimit(int slot) {
        return delegate.getSlotLimit(slot);
    }

    public ItemStack getFilter(int slot) {
        if (delegate instanceof IItemHandlerFiltered) {
            return ((IItemHandlerFiltered) delegate).getFilter(slot);
        }
        return IItemHandlerFiltered.super.getFilter(slot);
    }
	public boolean isItemValid(int slot, @NotNull ItemStack stack) {
		return this.delegate.isItemValid(slot, stack);
	}
}
