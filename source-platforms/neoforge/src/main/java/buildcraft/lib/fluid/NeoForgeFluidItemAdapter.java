package buildcraft.lib.fluid;

import buildcraft.api.v2.fluid.FluidItemAdapter;
import buildcraft.api.v2.fluid.FluidVolume;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * Resolves fluids exposed by items through NeoForge's standard fluid API.
 */
public final class NeoForgeFluidItemAdapter implements FluidItemAdapter {
    @Override
    public boolean supports(ItemStack stack) {
        return FluidUtil.getFluidContained(stack)
                .filter(fluid -> !fluid.isEmpty() && fluid.getAmount() > 0)
                .isPresent();
    }

    @Override
    public FluidVolume fluid(ItemStack stack) {
        FluidStack fluid = FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY);
        return FuelApiBridge.volumeOf(fluid);
    }
}