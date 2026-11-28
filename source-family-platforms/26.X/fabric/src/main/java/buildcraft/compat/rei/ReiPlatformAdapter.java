package buildcraft.compat.rei;

import buildcraft.lib.fluid.FluidCompatRegistry;
import java.util.List;
import me.shedaniel.rei.api.common.entry.EntryIngredient;
import me.shedaniel.rei.api.common.util.EntryStacks;
import buildcraft.lib.fluid.FluidStack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** Native fluid bridge for the Fabric JSON-registered REI client plugin. */
@Environment(EnvType.CLIENT)
final class ReiPlatformAdapter {
    private ReiPlatformAdapter() {}

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
