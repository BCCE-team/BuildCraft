package buildcraft.energy.client;

import buildcraft.energy.BCEnergyFluids;
import buildcraft.lib.fluid.BCFluid;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;

/** Uses 26.3's native FluidModel.Unbaked instead of NeoForge FluidType sprites. */
public final class FabricEnergyFluidRendering {
    private FabricEnergyFluidRendering() {}

    public static void bootstrap() {
        for (int family = 0; family < BCEnergyFluids.NAME.length; family++) {
            String name = BCEnergyFluids.NAME[family];
            for (int heat = 0; heat < BCEnergyFluids.HEAT_NAMES.length; heat++) {
                String path = "blocks/fluids/" + name + "/" + BCEnergyFluids.HEAT_NAMES[heat];
                BCFluid still = BCEnergyFluids.OIL_SOURCE.get(family * 3 + heat).get();
                BCFluid flowing = (BCFluid) still.getFlowing();
                FluidRenderingRegistry.register(still, flowing, new FluidModel.Unbaked(
                    new Material(Identifier.fromNamespaceAndPath("buildcraftenergy", path + "_still")),
                    new Material(Identifier.fromNamespaceAndPath("buildcraftenergy", path + "_flow")),
                    null, null));
            }
        }
        BCFluid spout = BCEnergyFluids.SPOUT_OIL_SOURCE.get();
        FluidRenderingRegistry.register(spout, BCEnergyFluids.SPOUT_OIL_FLOWING.get(),
            new FluidModel.Unbaked(
                new Material(Identifier.fromNamespaceAndPath("buildcraftenergy", "blocks/fluids/oil/cool_still")),
                new Material(Identifier.fromNamespaceAndPath("buildcraftenergy", "blocks/fluids/oil/cool_flow")),
                null, null));
    }
}
