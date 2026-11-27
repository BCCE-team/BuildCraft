package buildcraft.lib.fluid;

import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import buildcraft.lib.misc.InventoryUtil;
import buildcraft.energy.OilIndustry;
import buildcraft.core.BCCoreItems;
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
    /** Recover petroleum made unusable by CLASSIC, without touching any output or storage tank.
     * Keep valid third-party recipes, normal coolants and every mB of the original fluid.
     */
    public static void ejectInvalidClassicInput(Level level, BlockPos pos, Tank input) {
        if (!(level instanceof ServerLevel) || !OilIndustry.classic()) return;
        FluidStack fluid = input.getFluid();
        if (fluid.isEmpty()) return;
        var id = FuelApiBridge.variantOf(fluid).fluidId();
        if (!OilIndustry.isPetroleum(id.getNamespace(), id.getPath()) || input.isFluidValid(fluid)) return;

        // Prepare real shards before removing any fluid. The standard shard implementation preserves
        // the complete amount and variant, including a partial final shard.
        NonNullList<ItemStack> drops = NonNullList.create();
        BCCoreItems.FRAGILE_FLUID_SHARD.get().addFluidDrops(drops, fluid);
        if (drops.isEmpty()) return;
        input.drainInternal(fluid.getAmount(), FluidAction.EXECUTE);
        InventoryUtil.dropAll(level, pos, drops);
    }
}
