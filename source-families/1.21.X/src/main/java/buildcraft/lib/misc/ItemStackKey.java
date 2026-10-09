/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.misc;

import javax.annotation.Nonnull;

import net.minecraft.world.item.ItemStack;

public class ItemStackKey {
    public static final ItemStackKey EMPTY = new ItemStackKey(StackUtil.EMPTY);

    public final @Nonnull ItemStack baseStack;
    private final int hash;

    public ItemStackKey(@Nonnull ItemStack stack) {
        if (stack.isEmpty()) {
            baseStack = StackUtil.EMPTY;
            hash = 0;
        } else {
            this.baseStack = stack.copy();
            this.hash = StackUtil.hash(baseStack);
        }
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof ItemStackKey other)) return false;
        if (hash != other.hash) return false;
        return baseStack.getCount() == other.baseStack.getCount()
            && ItemStack.isSameItemSameComponents(baseStack, other.baseStack);
    }

    @Override
    public String toString() {
        return "[ItemStackKey " + baseStack + "]";
    }
}
