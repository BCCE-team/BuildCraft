package buildcraft.lib.fluid;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftRegistries;
import buildcraft.api.v2.fluid.FluidItemAdapter;
import buildcraft.api.v2.fluid.FluidVolume;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;

/** Resolves fluids exposed by items through Forge's standard fluid-container API. */
public final class ForgeFluidItemAdapter implements FluidItemAdapter {
    private ForgeFluidItemAdapter() {}

    public static void register() {
        BuildCraftApi.registry(BuildCraftRegistries.FLUID_ITEM_ADAPTERS).register(
            new ResourceLocation("buildcraft", "forge_fluid_container"),
            new ForgeFluidItemAdapter()
        );
    }

    @Override
    public boolean supports(ItemStack stack) {
        return FluidUtil.getFluidContained(stack)
            .filter(fluid -> !fluid.isEmpty() && fluid.getAmount() > 0)
            .isPresent();
    }

    @Override
    public FluidVolume fluid(ItemStack stack) {
        FluidStack fluid = FluidUtil.getFluidContained(stack).orElse(FluidStack.EMPTY);
        return fluid.isEmpty() ? FluidVolume.empty() : FuelApiBridge.volumeOf(fluid);
    }
}
