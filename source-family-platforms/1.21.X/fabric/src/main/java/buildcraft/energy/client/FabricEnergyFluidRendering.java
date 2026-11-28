package buildcraft.energy.client;

import buildcraft.energy.BCEnergyFluids;
import buildcraft.lib.fluid.BCFluid;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.minecraft.resources.Identifier;

/** Registers the pre-26.x Fabric fluid sprites, including every petroleum heat variant. */
public final class FabricEnergyFluidRendering {
    private FabricEnergyFluidRendering() {}
    private static SimpleFluidRenderHandler handler(String path) {
        return new SimpleFluidRenderHandler(Identifier.fromNamespaceAndPath("buildcraftenergy", path + "_still"),
            Identifier.fromNamespaceAndPath("buildcraftenergy", path + "_flow"));
    }
    public static void bootstrap() {
        for (int family = 0; family < BCEnergyFluids.NAME.length; family++) {
            for (int heat = 0; heat < BCEnergyFluids.HEAT_NAMES.length; heat++) {
                String path = "blocks/fluids/" + BCEnergyFluids.NAME[family] + "/" + BCEnergyFluids.HEAT_NAMES[heat];
                BCFluid still = BCEnergyFluids.OIL_SOURCE.get(family * 3 + heat).get();
                FluidRenderHandlerRegistry.INSTANCE.register(still, still.getFlowing(), handler(path));
            }
        }
        FluidRenderHandlerRegistry.INSTANCE.register(BCEnergyFluids.SPOUT_OIL_SOURCE.get(),
            BCEnergyFluids.SPOUT_OIL_FLOWING.get(), handler("blocks/fluids/oil/cool"));
    }
}
