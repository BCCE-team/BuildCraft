/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package buildcraft.compat.emi;

import buildcraft.lib.fluid.FluidCompatRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import java.util.ArrayList;
import java.util.List;
import net.minecraftforge.fluids.FluidStack;

/** Native fluid stack and quantity bridge for the optional EMI integration. */
final class EmiFluidAdapter {
    private EmiFluidAdapter() {}

    static EmiIngredient fluids(FluidStack stack) {
        if (stack == null || stack.isEmpty()) return EmiStack.EMPTY;
        var variants = FluidCompatRegistry.getEquivalentStacks(stack, "buildcraftenergy");
        List<EmiStack> entries = new ArrayList<>();
        for (var variant : variants.isEmpty() ? List.of(stack) : variants) {
            entries.add(outputFluid(variant));
        }
        return EmiIngredient.of(entries, nativeFluidAmount(stack.getAmount()));
    }

    static EmiStack outputFluid(FluidStack stack) {
        if (stack == null || stack.isEmpty()) return EmiStack.EMPTY;
        // Equivalent inputs are alternatives, but the machine produces one exact fluid.
        // Listing equivalent output fluids as separate outputs would double-count the recipe tree.
        return EmiStack.of(stack.getFluid(), stack.getTag(), nativeFluidAmount(stack.getAmount()));

    }

    static long nativeFluidAmount(long mb) {
        return mb;
    }
}
