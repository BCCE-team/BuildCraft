/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory.filter;

import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.lib.fluid.FluidCompatRegistry;

import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/** Returns true if the stack matches any one one of the filter stacks. */
public class ArrayFluidFilter implements IFluidFilter {

    protected FluidStack[] fluids;


    public ArrayFluidFilter(FluidStack... iFluids) {
        fluids = iFluids;
    }

    public ArrayFluidFilter(NonNullList<ItemStack> stacks) {
        fluids = new FluidStack[stacks.size()];

        for (int i = 0; i < stacks.size(); ++i) {
            FluidStack fluid = FluidUtil.getFluidContained(stacks.get(i)).orElse(FluidStack.EMPTY);
            if (!fluid.isEmpty() && fluid.getAmount() > 0) {
                fluids[i] = fluid;
            }
        }
    }

    public boolean hasFilter() {
        for (FluidStack filter : fluids) {
            if (filter != null) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(FluidStack fluid) {
        for (FluidStack filter : fluids) {
            if (filter != null && FluidCompatRegistry.areEquivalent(filter, fluid)) {
                return true;
            }
        }

        return false;
    }
}
