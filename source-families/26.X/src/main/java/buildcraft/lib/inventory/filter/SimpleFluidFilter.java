/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory.filter;

import buildcraft.lib.internal.core.IFluidFilter;
import buildcraft.lib.fluid.FluidCompatRegistry;

import net.neoforged.neoforge.fluids.FluidStack;

public class SimpleFluidFilter implements IFluidFilter {

    private FluidStack fluidChecked;

    public SimpleFluidFilter(FluidStack stack) {
        if (stack != null) {
            fluidChecked = stack;
        }
    }

    public boolean matches(FluidStack fluid) {
        if (fluidChecked != null) {
            return FluidCompatRegistry.areEquivalent(fluidChecked, fluid);
        } else {
            return fluid == null;
        }
    }
}
