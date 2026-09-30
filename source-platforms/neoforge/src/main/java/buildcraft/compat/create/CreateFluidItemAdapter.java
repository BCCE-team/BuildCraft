package buildcraft.compat.create;

import buildcraft.api.v2.fluid.FluidItemAdapter;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.fluid.FuelApiBridge;
import com.simibubi.create.content.fluids.potion.PotionFluidHandler;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

public final class CreateFluidItemAdapter implements FluidItemAdapter {
    @Override
    public boolean supports(ItemStack stack) {
        return PotionFluidHandler.isPotionItem(stack);
    }

    @Override
    public FluidVolume fluid(ItemStack stack) {
        FluidStack fluid = PotionFluidHandler.getFluidFromPotionItem(stack);
        return fluid.isEmpty() ? FluidVolume.empty() : FuelApiBridge.volumeOf(fluid);
    }
}
