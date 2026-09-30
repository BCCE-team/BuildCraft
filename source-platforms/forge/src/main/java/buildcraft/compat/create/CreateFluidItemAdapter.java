package buildcraft.compat.create;

import buildcraft.api.v2.fluid.FluidItemAdapter;
import buildcraft.api.v2.fluid.FluidVolume;
import buildcraft.lib.fluid.FuelApiBridge;
import com.simibubi.create.content.fluids.potion.PotionFluidHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fluids.FluidStack;

public final class CreateFluidItemAdapter implements FluidItemAdapter {
    @Override
    public boolean supports(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        //? if <1.20 {
        return stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION);
        //?} else {
        /*?
        // Create 0.5.1j knows about modded PotionItem subclasses and its create:not_potion exclusion tag.
        return PotionFluidHandler.isPotionItem(stack);
        ?*/
        //?}
    }

    @Override
    public FluidVolume fluid(ItemStack stack) {
        if (!supports(stack)) return FluidVolume.empty();
        FluidStack fluid = PotionFluidHandler.getFluidFromPotionItem(stack);
        return fluid.isEmpty() ? FluidVolume.empty() : FuelApiBridge.volumeOf(fluid);
    }
}
