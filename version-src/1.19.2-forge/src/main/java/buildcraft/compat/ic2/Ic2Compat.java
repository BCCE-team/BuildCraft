package buildcraft.compat.ic2;

import java.util.ArrayList;
import java.util.List;

import buildcraft.lib.internal.debug.BCLog;
import buildcraft.lib.platform.registry.BCRegistryEntry;
import buildcraft.compat.CompatCapTransfromer;
import buildcraft.energy.BCEnergy;
import buildcraft.energy.BCEnergyFluids;
import ic2.api.recipes.RecipeRegistry;
import ic2.api.recipes.registries.IFluidFuelRegistry;
import ic2.api.tiles.IFluidMachine;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.capability.IFluidHandler;

/** Optional IC2 Classic integration. This class must only be loaded when IC2 is present. */
public final class Ic2Compat {
    private static final List<BCRegistryEntry<? extends Item>> FLUID_CELLS = new ArrayList<>();
    private static boolean itemsRegistered;
    private static boolean initialized;

    private Ic2Compat() {}

    /**
     * Registers BuildCraft-fluid IC2 cells while the item registry is still open.
     * Called reflectively from {@link BCEnergy} only when IC2 Classic is loaded.
     */
    public static synchronized void registerItems() {
        if (itemsRegistered) {
            return;
        }
        itemsRegistered = true;

        int index = 0;
        for (String fluidName : BCEnergyFluids.NAME) {
            for (int heat = 0; heat < BCEnergyFluids.HEAT_NAMES.length; heat++) {
                final int fluidIndex = index++;
                final String fullName = fluidName + (heat == 0 ? "" : "_heat_" + heat);
                final String itemPath = "ic2_cell/" + fullName;
                FLUID_CELLS.add(BCEnergy.ITEMS.register(itemPath, () -> {
                    Fluid fluid = BCEnergyFluids.OIL_SOURCE.get(fluidIndex).get();
                    return new Ic2FluidCellItem(fluid);
                }));
            }
        }
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        registerFluidMachineBridge();
        registerFluidFuels();

        BCLog.logger.info("Enabled IC2 Classic fluid compatibility with {} BuildCraft fluid cells", FLUID_CELLS.size());
    }

    private static void registerFluidMachineBridge() {
        CompatCapTransfromer.INSTANCE.registerFluidCapFallback((tile, side) -> {
            if (!(tile instanceof IFluidMachine machine)) {
                return null;
            }
            IFluidHandler handler = machine.getConnectedTank(side);
            return handler == null ? null : new Ic2TankHandler(handler);
        });
    }

    /** Makes BuildCraft fuels directly usable by IC2's fluid-fuel machines. */
    private static void registerFluidFuels() {
        IFluidFuelRegistry serverRegistry = RecipeRegistry.FLUID_FUELS.get(true);
        IFluidFuelRegistry clientRegistry = RecipeRegistry.FLUID_FUELS.get(false);
        registerFluidFuels(serverRegistry);
        if (clientRegistry != serverRegistry) {
            registerFluidFuels(clientRegistry);
        }
    }

    private static void registerFluidFuels(IFluidFuelRegistry registry) {
        if (registry == null) {
            return;
        }
        addFuelFamily(registry, BCEnergyFluids.oilResidue, 3_000, 6);
        addFuelFamily(registry, BCEnergyFluids.crudeOil, 16_000, 16);
        addFuelFamily(registry, BCEnergyFluids.oilDistilled, 10_000, 10);
        addFuelFamily(registry, BCEnergyFluids.oilHeavy, 10_000, 10);
        addFuelFamily(registry, BCEnergyFluids.oilDense, 10_000, 10);
        addFuelFamily(registry, BCEnergyFluids.fuelGaseous, 44_992, 32);
        addFuelFamily(registry, BCEnergyFluids.fuelDense, 128_000, 64);
        addFuelFamily(registry, BCEnergyFluids.fuelMixedLight, 128_000, 64);
        addFuelFamily(registry, BCEnergyFluids.fuelMixedHeavy, 128_000, 64);
        addFuelFamily(registry, BCEnergyFluids.fuelLight, 128_000, 64);
    }

    private static void addFuelFamily(IFluidFuelRegistry registry, Fluid[] fluids, int totalEuPerBucket,
        int euPerTick) {
        int ticks = Math.max(1, (totalEuPerBucket + euPerTick - 1) / euPerTick);
        for (Fluid fluid : fluids) {
            if (fluid != null && registry.getFuel(fluid) == null) {
                registry.addFuel(fluid, ticks, euPerTick);
            }
        }
    }
}
