package buildcraft.compat.rei;

import buildcraft.lib.fluid.FluidCompatRegistry;
import java.util.List;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryStacks;
import net.minecraftforge.fluids.FluidStack;

/** Loader-discovered REI client entrypoint and native fluid bridge. */
@me.shedaniel.rei.forge.REIPluginClient
public final class ReiPlatformAdapter extends BuildCraftReiPlugin {

    static EntryIngredient fluids(FluidStack stack) {
        if (stack == null || stack.isEmpty()) return EntryIngredient.empty();
        var variants = FluidCompatRegistry.getEquivalentStacks(stack, "buildcraftenergy");
        var entries = EntryIngredient.builder();
        for (var variant : variants.isEmpty() ? List.of(stack) : variants) {
            entries.add(EntryStacks.of(variant.getFluid(), (long) variant.getAmount()
                * dev.architectury.fluid.FluidStack.bucketAmount() / 1000));
        }
        return entries.build();
    }
}
