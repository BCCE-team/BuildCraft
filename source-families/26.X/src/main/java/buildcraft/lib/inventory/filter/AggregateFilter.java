/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory.filter;

import javax.annotation.Nonnull;

import buildcraft.lib.internal.core.IStackFilter;

import net.minecraft.world.item.ItemStack;

/** Returns true if the stack matches all of the stack filters. */
public class AggregateFilter implements IStackFilter {

    private final IStackFilter[] filters;

    public AggregateFilter(IStackFilter... iFilters) {
        filters = iFilters;
    }

    public boolean matches(@Nonnull ItemStack stack) {
        for (IStackFilter f : filters) {
            if (!f.matches(stack)) {
                return false;
            }
        }

        return true;
    }
}
