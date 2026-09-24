package buildcraft.energy;

import buildcraft.lib.platform.registry.BCRegistryEntry;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraftforge.client.model.generators.BlockModelProvider;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.client.model.generators.ConfiguredModel;
import net.minecraftforge.client.model.generators.ItemModelProvider;
import net.minecraftforge.client.model.generators.ModelFile;
import net.minecraftforge.client.model.generators.loaders.DynamicFluidContainerModelBuilder;
import net.minecraftforge.common.data.ExistingFileHelper;

public final class BCEnergyProvider {
    private BCEnergyProvider() {
    }

    static class BlockState extends BlockStateProvider {
        public BlockState(PackOutput output, ExistingFileHelper helper) {
            super(output, BCEnergy.MODID, helper);
        }

        @Override
        protected void registerStatesAndModels() {
            for (int id = 0; id < BCEnergyFluids.NAME.length; id++) {
                for (int heat = 0; heat < BCEnergyFluids.HEAT_NAMES.length; heat++) {
                    String name = BCEnergyFluids.NAME[id];
                    BCRegistryEntry<LiquidBlock> registryObject = BCEnergyFluids.OIL_BLOCK.get(3 * id + heat);
                    simpleBlock(registryObject.get(), new ConfiguredModel(
                        models().getBuilder(BCEnergy.MODID + ":fluids/" + name + "/" + BCEnergyFluids.HEAT_NAMES[heat])
                    ));
                }
            }
        }
    }

    static class BlockModel extends BlockModelProvider {
        public BlockModel(PackOutput output, ExistingFileHelper helper) {
            super(output, BCEnergy.MODID, helper);
        }

        @Override
        protected void registerModels() {
            for (String name : BCEnergyFluids.NAME) {
                for (String heatName : BCEnergyFluids.HEAT_NAMES) {
                    getBuilder(BCEnergy.MODID + ":fluids/" + name + "/" + heatName)
                        .texture("particle", BCEnergy.MODID + ":blocks/fluids/" + name + "/" + heatName + "_still");
                }
            }
        }
    }

    static class ItemModel extends ItemModelProvider {
        public ItemModel(PackOutput output, ExistingFileHelper helper) {
            super(output, BCEnergy.MODID, helper);
        }

        @Override
        protected void registerModels() {
            for (int id = 0; id < BCEnergyFluids.NAME.length; id++) {
                for (int heat = 0; heat < BCEnergyFluids.HEAT_NAMES.length; heat++) {
                    String name = BCEnergyFluids.NAME[id];
                    getBuilder(BCEnergy.MODID + ":item/" + name + "/" + BCEnergyFluids.HEAT_NAMES[heat] + "_bucket")
                        .parent(new ModelFile.UncheckedModelFile("forge:item/bucket_drip"))
                        .customLoader(DynamicFluidContainerModelBuilder::begin)
                        .applyTint(false)
                        .flipGas(true)
                        .fluid(BCEnergyFluids.OIL_SOURCE.get(3 * id + heat).get())
                        .end();
                }
            }
        }
    }
}
