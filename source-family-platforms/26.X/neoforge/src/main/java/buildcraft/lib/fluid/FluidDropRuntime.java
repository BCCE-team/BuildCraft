package buildcraft.lib.fluid;

import buildcraft.api.v2.BuildCraftApi;
import buildcraft.api.v2.BuildCraftServices;
import buildcraft.api.v2.drop.FluidDropContext;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.IFluidTank;

/** Loader bridge from classic NeoForge fluid stacks/tanks to the API2 fluid-drop service. */
public final class FluidDropRuntime {
    private FluidDropRuntime() {}

    public static void addFluidDrops(NonNullList<ItemStack> toDrop, FluidStack... fluids) {
        if (toDrop == null || fluids == null) return;
        for (FluidStack fluid : fluids) {
            if (fluid == null || fluid.isEmpty()) continue;
            for (ItemStack drop : BuildCraftApi.service(BuildCraftServices.FLUID_DROPS)
                .createDrops(FluidDropContext.of(FuelApiBridge.volumeOf(fluid)))) {
                if (drop != null && !drop.isEmpty()) toDrop.add(drop.copy());
            }
        }
    }

    public static void addFluidDrops(NonNullList<ItemStack> toDrop, IFluidTank... tanks) {
        if (tanks == null) return;
        for (IFluidTank tank : tanks) if (tank != null) addFluidDrops(toDrop, tank.getFluid());
    }
}
