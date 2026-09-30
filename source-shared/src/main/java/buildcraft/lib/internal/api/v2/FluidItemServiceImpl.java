package buildcraft.lib.internal.api.v2;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.fluid.FluidItemAdapter;
import buildcraft.api.v2.fluid.FluidItemService;
import buildcraft.api.v2.fluid.FluidVolume;
import net.minecraft.world.item.ItemStack;

/** Runtime resolver for registry-provided item-to-fluid adapters. */
public final class FluidItemServiceImpl implements FluidItemService {
    @Override
    public FluidVolume fluid(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return FluidVolume.empty();
        }

        for (FluidItemAdapter adapter : BuildCraftApi.registry(BuildCraftRegistries.FLUID_ITEM_ADAPTERS).values()) {
            if (!adapter.supports(stack)) {
                continue;
            }

            FluidVolume fluid = adapter.requireFluid(stack);
            if (!fluid.isEmpty()) {
                return fluid;
            }
        }

        return FluidVolume.empty();
    }
}